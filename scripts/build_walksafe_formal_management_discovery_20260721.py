#!/usr/bin/env python3
"""Build WalkSafe MGT/DSC formal Draft deliverables from the approved policy."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
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
POLICY_APPROVAL_VALIDATOR_PATH = Path(approval_builder.GENERATOR_PATH).resolve()
DECISION_ALIGNMENT_VALIDATOR_PATH = Path(alignment_builder.GENERATOR_PATH).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"
MGT_DIR = DELIVERABLES_DIR / "01-management"
DSC_DIR = DELIVERABLES_DIR / "02-discovery"
MANIFEST_DIR = DELIVERABLES_DIR / "manifests"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
POLICY_APPROVAL_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
POLICY_MANIFEST_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
FP035_CORRECTION_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
ALIGNED_DECISIONS_PATH = CONTROL_DIR / "decision-interview" / "walksafe-effective-decision-register-aligned-20260721-r001.json"
CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
PROJECT_DECISION_ANSWERS_PATH = (
    CONTROL_DIR
    / "questionnaire"
    / "source-records"
    / "walksafe-project-decisions-20260717-answers.json"
)

CHARTER_PATH = MGT_DIR / "project-charter.md"
PMP_PATH = MGT_DIR / "project-management-plan.md"
CONTROL_REGISTER_PATH = MGT_DIR / "project-control-registers.md"
MGT_REGISTER_DIR = MGT_DIR / "registers"
WBS_PATH = MGT_REGISTER_DIR / "wbs.json"
SCHEDULE_PATH = MGT_REGISTER_DIR / "schedule.json"
STAKEHOLDERS_PATH = MGT_REGISTER_DIR / "stakeholders.json"
RACI_PATH = MGT_REGISTER_DIR / "raci.json"
RAID_PATH = MGT_REGISTER_DIR / "raid.json"
DECISIONS_PATH = MGT_REGISTER_DIR / "decisions.json"
CHANGES_PATH = MGT_REGISTER_DIR / "change-requests.json"
ACTIONS_PATH = MGT_REGISTER_DIR / "actions.json"
DASHBOARD_PATH = MGT_REGISTER_DIR / "dashboard.json"

DISCOVERY_PATH = DSC_DIR / "discovery-evidence-and-analysis.md"
PRODUCT_PATH = DSC_DIR / "product-definition.md"
PRODUCT_REGISTERS_PATH = DSC_DIR / "product-registers.md"
DSC_REGISTER_DIR = DSC_DIR / "registers"
DISCOVERY_EVIDENCE_PATH = DSC_REGISTER_DIR / "discovery-evidence.json"
BACKLOG_PATH = DSC_REGISTER_DIR / "backlog.json"
STAGE_DECISIONS_PATH = DSC_REGISTER_DIR / "stage-decisions.json"

MANIFEST_PATH = MANIFEST_DIR / "management-discovery-draft-20260721-r001.json"

AS_OF = "2026-07-22"
VERSION = "0.3.0"
RELEASE_STATUS = "NOT_ELIGIBLE"
HISTORICAL_ARTIFACT_CATALOG_SHA256 = "dcd998b930a2e7c80f1dd2339a690069392357fee9f16408d8a2f10a8ec18136"
FP035_NETWORK_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_CORRECTION_CANDIDATE_ID = "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
FP035_REQUIRED_ACTIVATION_EVENT = "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT"
FP035_DIRECT_ARTIFACT_CODES = ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"]
FP035_DIRECT_DESIGN_IDS = ["DES-04", "DES-13", "DES-20"]
FP035_RELATED_DOWNSTREAM_IDS = ["DES-09"]

MGT_COVERAGE = {
    "project-charter.md": [f"MGT-{number:02d}" for number in range(1, 5)],
    "project-management-plan.md": [f"MGT-{number:02d}" for number in range(5, 14)],
    "project-control-registers.md": [f"MGT-{number:02d}" for number in range(14, 19)],
}
DSC_COVERAGE = {
    "discovery-evidence-and-analysis.md": [f"DSC-{number:02d}" for number in range(1, 10)],
    "product-definition.md": [f"DSC-{number:02d}" for number in range(10, 14)],
    "product-registers.md": ["DSC-14", "DSC-15"],
}

ARTIFACT_SUPPORT_PATHS: dict[str, list[Path]] = {
    "MGT-06": [WBS_PATH],
    "MGT-07": [SCHEDULE_PATH],
    "MGT-09": [STAKEHOLDERS_PATH],
    "MGT-10": [RACI_PATH],
    "MGT-14": [RAID_PATH],
    "MGT-15": [DECISIONS_PATH],
    "MGT-16": [CHANGES_PATH],
    "MGT-17": [DASHBOARD_PATH],
    "MGT-18": [ACTIONS_PATH],
    **{f"DSC-{number:02d}": [DISCOVERY_EVIDENCE_PATH] for number in range(1, 10)},
    "DSC-14": [BACKLOG_PATH],
    "DSC-15": [STAGE_DECISIONS_PATH],
}

ARTIFACT_CURRENT_STATUS = {
    "MGT-08": "DRAFT_ACTIVE_NO_SEPARATE_PROJECT_BUDGET",
    "DSC-03": "DRAFT_PLAN_EXECUTION_NOT_RUN",
    "DSC-04": "PLANNED_NOT_RUN",
    "DSC-05": "DRAFT_POLICY_PROFILE_ONLY",
    "DSC-07": "PLANNED_NOT_RUN",
    "DSC-09": "DRAFT_CANDIDATE_EVIDENCE_ONLY",
}


class ManagementDiscoveryError(ValueError):
    """Raised when controlled inputs or generated Drafts are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ManagementDiscoveryError(message)


def _reject_constant(value: str) -> None:
    raise ManagementDiscoveryError(f"non-standard JSON number is not allowed: {value}")


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
        raise ManagementDiscoveryError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha_file(path: Path) -> str:
    return _sha_bytes(path.read_bytes())


def _object_sha(value: Any) -> str:
    return _sha_bytes(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _binding(path: Path) -> dict[str, str]:
    return {"path": _rel(path), "sha256": _sha_file(path)}


def _metadata(codes: list[str], title: str) -> dict[str, Any]:
    return {
        "title": title,
        "version": VERSION,
        "as_of": AS_OF,
        "lifecycle_status": "DRAFT",
        "freshness_status": "CURRENT_DRAFT",
        "verification_status": "STRUCTURE_CHECKED_CONTENT_REVIEW_PENDING",
        "approval_status": "NOT_APPROVED",
        "release_status": RELEASE_STATUS,
        "artifact_type_ids": codes,
        "source_policy_baseline": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
        "generated_by": _rel(GENERATOR_PATH),
    }


def _header(title: str, codes: list[str], question: str) -> str:
    return f"""# {title}

> 포함 산출물: {', '.join(codes)}  
> 버전: {VERSION} · 상태: Draft · 승인: 미승인  
> 기준 정책 문서: WalkSafe 기능 정책 1.0.0  
> 남은 필수 검증: 5개 모두 미실행·면제 없음 · 출시: 불가(`NOT_ELIGIBLE`)

## 이 문서가 답하는 질문

{question}

## 쉬운 요약

기능이 어떤 원칙으로 작동할지 정한 정책 문서 1.0.0은 승인됐습니다. 이 파일은 그 정책을 프로젝트 운영과 제품 기획에 옮긴 **검토용 초안**입니다. 저장소에 이미 있는 코드·설정은 존재를 확인한 후보일 뿐이고, 실제로 시험하지 않은 결과는 완료로 표시하지 않습니다.
"""


def _bullet(values: Iterable[str], empty: str = "해당 없음") -> str:
    items = list(values)
    return "\n".join(f"- {item}" for item in items) if items else f"- {empty}"


def _review_cycle(code: str, recommended_form: str) -> str:
    if code == "MGT-17":
        return "활성 작업일마다 관측시각을 갱신하고, 최소 주 1회 프로젝트책임자가 확인"
    if code in {"MGT-07", "MGT-14", "MGT-16", "MGT-18", "DSC-08", "DSC-14", "DSC-15"}:
        return "관련 사건 발생 즉시 갱신하고, 최소 주 1회 및 각 마일스톤 종료 때 검토"
    if code in {"DSC-03", "DSC-04", "DSC-05", "DSC-07", "DSC-09"}:
        return "조사·분석·PoC 단계 시작 전과 각 실행 묶음 종료 뒤 검토"
    if recommended_form == "REGISTER":
        return "새 항목 또는 상태변경 때 즉시 갱신하고 매주 검토"
    return "마일스톤 종료, 상위 기준선 변경 또는 완료기준 영향 사건 때 검토"


def _replacement_rule(recommended_form: str) -> str:
    if recommended_form == "REGISTER":
        return "과거 행을 지우지 않고 정정·대체 사건을 추가한다. 기준선 시점 snapshot은 읽기 전용으로 보관하고 새 snapshot이 이전 snapshot을 supersedes로 연결한다."
    if recommended_form == "GENERATED_EVIDENCE":
        return "사람이 생성 결과를 직접 고치지 않는다. 입력과 생성기를 수정해 새 revision을 만들고 이전 결과는 지문과 함께 Archived로 보존한다."
    return "승인 전 Draft는 변경이력을 남겨 갱신한다. 승인 뒤에는 원본을 덮어쓰지 않고 새 버전과 supersedes 링크를 만들며, 폐기본은 Superseded 뒤 Archived로 보존한다."


def _artifact_management_section(codes: list[str], primary_path: Path) -> str:
    catalog = load_strict_json(CATALOG_PATH)
    by_code = {item["display_code"]: item for item in catalog["artifact_types"]}
    sections = [
        "## 산출물별 작성·관리 규칙",
        "",
        "아래 규칙은 이 묶음 안의 각 산출물을 어떻게 작성하고 계속 관리할지 정합니다. 현재 상태가 `Draft` 또는 `Planned/NOT_RUN`인 항목은 완료·승인을 주장하지 않습니다.",
    ]
    for code in codes:
        item = by_code[code]
        support_paths = ARTIFACT_SUPPORT_PATHS.get(code, [])
        paths = [primary_path, *support_paths]
        unique_paths = list(dict.fromkeys(_rel(path) for path in paths))
        upstream = [value.removeprefix("DLV-") for value in item.get("upstream_types", [])]
        current_status = ARTIFACT_CURRENT_STATUS.get(code, "DRAFT_ACTIVE")
        reviewers = ", ".join(item.get("reviewer_roles", [])) or "지정 검토자 없음"
        contents = "; ".join(item.get("required_contents", []))
        inputs = "; ".join(item.get("required_inputs", []))
        completion = "; ".join(item.get("completion_criteria", []))
        update_triggers = "; ".join(item.get("update_triggers", []))
        applicability = f"{item['default_applicability']} — {item['activation_condition']}"
        sections.extend(
            [
                "",
                f"### {code} {item['title']}",
                "",
                f"- **작성 목적:** {item['purpose']}",
                f"- **필수·조건부와 적용 조건:** {applicability}",
                f"- **현재 상태:** `{current_status}`",
                f"- **들어갈 내용:** {contents}",
                f"- **입력자료:** {inputs}",
                f"- **선후관계:** 선행 {', '.join(upstream) if upstream else '없음'}; 후속 {', '.join(value.removeprefix('DLV-') for value in item.get('downstream_types', [])) or '없음'}",
                f"- **역할:** 작성 {item['owner_role']}; 검토 {reviewers}; 승인 {item['approver_role']}. 내부 프로젝트 역할은 김민호가 겸임하되, 정책상 독립 검토가 필요한 역할은 미배정 상태를 숨기지 않습니다.",
                f"- **형식·보관 위치:** {item['recommended_form']}; {', '.join(unique_paths)}",
                f"- **완료·승인 기준:** {completion}",
                f"- **갱신 조건·주기:** {update_triggers}; {_review_cycle(code, item['recommended_form'])}",
                f"- **변경·폐기·대체:** {_replacement_rule(item['recommended_form'])}",
            ]
        )
    return "\n".join(sections)


def _validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    policy = load_strict_json(POLICY_PATH)
    approval = load_strict_json(POLICY_APPROVAL_PATH)
    manifest = load_strict_json(POLICY_MANIFEST_PATH)
    decisions = load_strict_json(ALIGNED_DECISIONS_PATH)
    catalog = load_strict_json(CATALOG_PATH)
    project_answers = load_strict_json(PROJECT_DECISION_ANSWERS_PATH)
    correction = load_strict_json(FP035_CORRECTION_PATH)
    policy_body = {key: value for key, value in policy.items() if key != "document_content_sha256"}
    _require(
        policy.get("document_content_sha256") == _object_sha(policy_body),
        "policy document content hash differs from its actual body",
    )
    _require(policy.get("document_content_sha256") == "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28", "policy digest differs")
    _require(len(policy.get("features", [])) == 54 and len(policy.get("common_policies", [])) == 9, "policy coverage differs")
    _require(len(policy.get("remaining_gates", [])) == 5, "policy gate count differs")
    boundary = manifest.get("establishment_boundary", {})
    _require(manifest.get("metadata", {}).get("lifecycle_status") == "BASELINED", "policy is not baselined")
    _require(boundary.get("formal_deliverables_authorized") is True, "formal authoring is not authorized")
    _require(boundary.get("remaining_gates_are_waived") is False and boundary.get("release_status") == RELEASE_STATUS, "approval boundary differs")
    try:
        approval_builder.validate_approval_record(approval)
        approval_builder.validate_baseline_manifest(manifest, approval)
    except (
        approval_builder.BaselineApprovalError,
        approval_builder.review_builder.BaselineReviewError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
    ) as exc:
        raise ManagementDiscoveryError(
            f"policy baseline approval validation failed: {exc}"
        ) from exc
    decision_boundary = decisions.get("approval_boundary", {})
    _require(len(decisions.get("decisions", [])) == 135, "decision count differs")
    _require(decision_boundary.get("policy_alignment_status") == "COMPLETE", "decision alignment is incomplete")
    _require(decision_boundary.get("artifact_approval_status") == "NOT_APPROVED", "aligned register approval boundary differs")
    try:
        alignment_sources = alignment_builder._load_and_validate_sources()
        alignment_builder.validate_alignment(decisions, alignment_sources)
    except (
        alignment_builder.DecisionAlignmentError,
        alignment_builder.review_builder.BaselineReviewError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
    ) as exc:
        raise ManagementDiscoveryError(
            f"aligned decision register validation failed: {exc}"
        ) from exc
    types = catalog.get("artifact_types", [])
    mgt = [item for item in types if item.get("category") == "MGT"]
    dsc = [item for item in types if item.get("category") == "DSC"]
    _require(len(mgt) == 18 and len(dsc) == 15, "MGT/DSC catalog coverage differs")
    expected_mgt = {item["display_code"] for item in mgt}
    expected_dsc = {item["display_code"] for item in dsc}
    _require(expected_mgt == {code for values in MGT_COVERAGE.values() for code in values}, "MGT document coverage differs")
    _require(expected_dsc == {code for values in DSC_COVERAGE.values() for code in values}, "DSC document coverage differs")
    _require(
        project_answers.get("question_set_id") == "walksafe-project-decisions-20260717",
        "project-decision answer set differs",
    )
    _require(
        project_answers.get("answers", {}).get("Q-GOV-011") == "milestone"
        and project_answers.get("answers", {}).get("Q-OPS-002") == "business_hours",
        "project schedule/support answer differs",
    )
    planning_note = project_answers.get("notes", {}).get("Q-GOV-011", "")
    _require(
        "정해진 예산은 없" in planning_note and "7월 26일" in planning_note,
        "project budget/demo source note differs",
    )
    _require(
        correction.get("metadata", {}).get("candidate_id") == FP035_CORRECTION_CANDIDATE_ID
        and correction.get("metadata", {}).get("approval_status") == "NOT_APPROVED"
        and correction.get("metadata", {}).get("effective_status") == "NOT_EFFECTIVE",
        "FP-035 correction candidate boundary differs",
    )
    _require(
        correction.get("correction", {}).get("affected_artifact_codes")
        == FP035_DIRECT_ARTIFACT_CODES,
        "FP-035 direct artifact set differs",
    )
    return policy, manifest, decisions, catalog


def _gate_rows(policy: dict[str, Any]) -> str:
    return "\n".join(
        f"| {gate['id']} | {gate['title']} | {gate['status']} | {gate['closure']} |"
        for gate in policy["remaining_gates"]
    )


def _charter(policy: dict[str, Any]) -> str:
    by_id = {feature["id"]: feature for feature in policy["features"]}
    areas = "\n".join(f"| {area['id']} | {area['title']} | {area['plain_scope']} |" for area in policy["areas"])
    return _header(
        "WalkSafe 프로젝트 헌장",
        MGT_COVERAGE["project-charter.md"],
        "왜 이 프로젝트를 하고, 무엇을 성공으로 보며, 어디까지 만들고 어디부터는 제외하는가?",
    ) + f"""

<a id="mgt-01"></a>
## MGT-01 프로젝트 헌장

WalkSafe는 시각장애인의 일반 도심 보행 중 가까운 위험과 큰 이동 방향을 음성·진동으로 알리고, 손상 점자블록 신고를 돕는 Android 보행 보조 서비스입니다. 프로젝트관리자·제품책임자·서비스관리자·내부 개발책임자는 **김민호 한 명**이 겸임합니다. 이 헌장은 승인된 정책을 관리·요구·설계·구현·시험 산출물로 옮기는 권한과 경계를 정합니다.

현재 기능 정책 1.0.0은 승인됐습니다. 그러나 코드 구현, 시험 결과, 출시를 승인한 것은 아니며 이 문서는 아직 사람 검토 전 초안입니다.

### 착수 근거와 종료 조건

- 착수 근거: 한이음 드림업 프로젝트에서 Android 사용자 앱·비공개 관리자 앱과 서버를 하나의 운영 가능한 보행 보조 서비스로 정의하고, 승인 정책 63개와 결정 135개를 정식 산출물로 전환하도록 승인받았습니다.
- 2026년 7월 26일 최종 시연은 **통제된 통합 시연 마일스톤**입니다. 이 날짜가 정식 출시 승인을 뜻하지 않습니다.
- 프로젝트 완료는 범위 산출물 인계, 미해결 결함·잔여위험·기술부채 공개, 계정·키·데이터 처리계획 확인과 최종 인수 기록이 모두 있을 때만 선언합니다.
- 중단 또는 범위전환은 안전 차단결함, 법률·개인정보 차단, 핵심 외부의존 불가, 자원 한계가 해소되지 않을 때 MGT-14·15·16에 근거와 후속조치를 남겨 김민호가 결정합니다.

### 핵심 이해관계자와 권한

| 집단 | 권한·관심 | 현재 참여 상태 |
|---|---|---|
| 김민호 | 프로젝트·제품·운영 최종 결정, 내부 개발·문서 작성 | 지정됨 |
| 전맹·저시력 사용자 | 사용성·접근성·현장안전 근거 제공 | 실제 조사·시험 `NOT_RUN` |
| 안전·접근성·보안·개인정보 독립 검토자 | 해당 gate와 전문 검토 | 미배정 |
| 한이음 멘토·평가/인수 주체 | 지도·외부 검토·인수 | 구체 서명범위 확인 필요 |
| TMAP·Google Cloud·신고기관 | 경로·저장·기관전달 외부 의존 | 실제 운영계약·시험 미완료 |

<a id="mgt-02"></a>
## MGT-02 사업 필요성·기대효과

### 해결할 문제

{by_id['FP-001']['plain_summary']}

시각장애 사용자는 화면을 계속 보지 않고 가까운 위험, 목적지 방향, 오류와 기능 중단을 알아야 합니다. 기존 보행 보조수단을 대신한다고 과장하지 않으면서, 휴대전화의 카메라·센서·음성과 지도 서비스를 한 흐름으로 묶는 것이 필요합니다.

### 기대효과

- 가까운 위험과 이동 방향을 접근 가능한 방식으로 빠르게 알림
- 손상 점자블록 신고 후보를 중복 없이 운영 흐름으로 연결
- 정책→요구→설계→코드→시험을 추적해 잘못된 안전 주장을 방지
- 수집 원본·권한·삭제·관리자 복구의 책임과 차단조건을 문서화

기대효과는 아직 현장 성과로 입증된 결과가 아니라 시험으로 확인할 목표입니다.

### 비교한 대안과 선택 이유

| 대안 | 판단 | 이유 |
|---|---|---|
| 기존 Web/PWA를 정식 제품으로 계속 사용 | 제외 | 승인된 제품 경계가 Android native로 바뀌었고 센서·권한·배포 시험 기준이 다름 |
| 객체탐지 시연만 완성 | 제외 | 길안내·접근성·신고·데이터·운영 책임이 닫히지 않음 |
| Android 사용자 앱만 만들고 운영 기능 제외 | 제외 | 자동신고 검수·데이터 권리·장애 대응을 책임질 수 없음 |
| Android 사용자 앱+비공개 관리자 앱+서버를 단계적으로 검증 | 채택 | 승인 정책과 단계 배포·안전정지·운영 증거를 함께 충족할 수 있음 |

효과는 시연 성공 여부만으로 판단하지 않고 DSC-11의 과업 성공·안전·접근성·운영 KPI와 실제 조사·시험 증거로 측정합니다.

<a id="mgt-03"></a>
## MGT-03 목표와 성공지표

| 목표 | 성공으로 인정할 조건 | 현재 |
|---|---|---|
| 7월 26일 통합 시연 | 통제환경에서 대표 위험안내·길안내·신고 흐름을 같은 후보 구성으로 시연하고 제한사항을 고지 | 준비 중; 출시 증거로 사용 금지 |
| 정책 완전성 | 공통정책 9개·기능 54개·결정 135개가 추적표에서 빠짐없이 연결 | 정책·결정 정렬 완료, formal 산출물 검토 전 |
| 기능 구현 | 필수 요구가 승인 설계와 이름 붙인 build에 연결 | 미검증 |
| 접근성 | TalkBack·음성·진동·터치·오류창 시험 통과 | NOT_RUN |
| 안전 | 실기기·현장·오탐/미탐·장애·복구 기준 통과 | NOT_RUN |
| 개인정보 | 동의·원본·보존·삭제·권리행사 E2E와 독립 검토 통과 | NOT_RUN |
| 운영 | 단일 관리자 복구·용량·비용·관측 기준 통과 | NOT_RUN |
| 출시 | TST-22 Go와 지정 인수자의 TST-23 서명 | NOT_ELIGIBLE |

최우선 성과는 과업 성공이며 안전 KPI를 균형 있게 적용합니다. 수치 임계값이 필요한 성능·용량 항목은 사전 동결한 측정 protocol로 기준값을 얻은 뒤 목표값을 승인합니다. 서버 저장비는 월 30,000원 상한을 이미 사용하고 실제 충족 여부만 gate에서 측정합니다.

<a id="mgt-04"></a>
## MGT-04 범위·제외 범위

### 정식 제품과 서비스 범위

- Android 사용자 앱: 카메라 탐지, 길안내, 음성·진동, 접근성, 신고, 원본수집 동의·철회
- 별도 비공개 Android 관리자 앱: 신고 검수·상태·기관 전달·운영 통제
- 백엔드·PostgreSQL/PostGIS·대용량 원본 저장소·TMAP 중계
- 휴대전화 우선 객체탐지·위험판정·STT/TTS, 서버 학습·평가·모델 교체
- 정책의 18개 기능 영역

### 제외·제한

- Web/PWA는 legacy 참고자료이며 정식 사용자·관리자 제품이 아님
- 보호자 실시간 추적과 넘어짐 탐지는 첫 정식 범위에서 제외
- 흰지팡이·안내견을 대체하거나 보행 안전을 보장한다는 주장 제외
- 5개 gate와 현장·보안·릴리스 선행조건을 통과하지 않은 공개 출시 제외

### 범위 변경 권한

김민호가 제품 범위와 MVP의 최종 승인자입니다. 승인된 정책·요구·설계 기준선에 영향을 주는 변경만 정식 변경요청 대상으로 하며, 아이디어·메모 수준 변경은 Backlog에서 먼저 평가합니다. 독립 검토나 외부 인수가 필요한 사실을 1인 승인으로 대체하지 않습니다.

## 기능 영역 전체 보기

| 영역 | 이름 | 다루는 범위 |
|---|---|---|
{areas}

## 주요 제약·가정·위험

- 한 명의 관리자 체계이므로 MFA/패스키·외부 복구수단·고위험 작업 동결과 실제 복구훈련이 필요합니다.
- 가리지 않은 영상·음성·정확 위치 원본을 수집하므로 독립 출시 검토가 필요합니다.
- 휴대전화 queue, 서버 용량상태, 클라우드 비용은 실측 전입니다.
- TMAP·센서·카메라·모델·네트워크 실패 때 불완전한 안내를 계속하지 않아야 합니다.

## 승인 경계

이 문서의 사람 검토와 승인은 아직 없습니다. 정책 기준선 승인자를 이 헌장의 승인자로 자동 복제하지 않습니다.
""" + "\n" + _artifact_management_section(MGT_COVERAGE["project-charter.md"], CHARTER_PATH)


def _wbs() -> dict[str, Any]:
    work = [
        ("WBS-0", "통제 기반", "DOC-01~05", [], "S", "BASELINE_CONTROL", "통제 규칙과 대장 구조 검토"),
        ("WBS-1", "관리·제품기획", "MGT-01~18, DSC-01~15", ["WBS-0"], "L", "MVP_FOUNDATION", "헌장·제품범위·우선순위 Draft 검토"),
        ("WBS-2", "요구 기준선 준비", "REQ-01~19", ["WBS-1"], "XL", "MVP_FOUNDATION", "68개 정책/gate 요구와 인수조건·RTM 검토"),
        ("WBS-3", "시험계획 선행", "TST-01~05, TST-18", ["WBS-2"], "L", "MVP_FOUNDATION", "요구별 case·환경·결함원장 검토"),
        ("WBS-4", "설계", "DES-01~27", ["WBS-2", "WBS-3"], "XL", "MVP_FOUNDATION", "Android 중심 설계와 데이터·보안·장애 경계 검토"),
        ("WBS-5", "구현 정렬", "DEV-01~21", ["WBS-4"], "XL", "MVP_IMPLEMENTATION", "현재 후보를 재사용·수정·폐기·legacy로 판정"),
        ("WBS-6", "시험 실행", "TST-06~21", ["WBS-5"], "XL", "MVP_VERIFICATION", "이름 붙인 build에서 증거·결함·잔여위험 생성"),
        ("WBS-7", "출시 준비도·인수", "TST-22~23 + SEC/REL/WS 입력", ["WBS-6"], "L", "RELEASE", "5 gate 종결 뒤 사람의 Go/인수 결정"),
    ]
    return {
        "schema_version": "walksafe.wbs.v1",
        "metadata": _metadata(["MGT-06"], "WalkSafe 작업분해구조"),
        "work_packages": [
            {
                "wbs_id": item[0],
                "name": item[1],
                "artifact_scope": item[2],
                "depends_on": item[3],
                "relative_effort": item[4],
                "delivery_class": item[5],
                "owner_role": "프로젝트·실무책임자",
                "assigned_person": "김민호",
                "reviewer_role": "프로젝트책임자",
                "exit_condition": item[6],
                "milestone_id": "MS-02" if item[0] in {"WBS-0", "WBS-1", "WBS-2", "WBS-3", "WBS-4"} else "MS-04" if item[0] == "WBS-5" else "MS-05" if item[0] == "WBS-6" else "MS-07",
                "status": "IN_PROGRESS" if wbs_id in {"WBS-0", "WBS-1", "WBS-2", "WBS-3", "WBS-4", "WBS-5"} else "PLANNED",
                "completion_claimed": False,
            }
            for item in work
            for wbs_id in [item[0]]
        ],
        "effort_scale": {"S": "작음", "L": "큼", "XL": "매우 큼"},
        "planning_note": "1인 프로젝트의 상대 공수이며 시간·비용 실적이 아니다. 요구·설계 기준선 뒤 김민호가 다시 추정한다.",
    }


def _schedule() -> dict[str, Any]:
    milestones = [
        ("MS-01", "정책 기준선 확정", [], "COMPLETE", "승인 receipt·manifest 무결성 통과", "2026-07-21", "2026-07-21", "승인 기록"),
        ("MS-02", "0~6 Draft 작성·정합성 검토", ["MS-01"], "IN_PROGRESS", "0~6 범위 128개 유형 모두 정본 경로·장 위치와 실질 내용 보유", None, None, "Goal 수행 결과로 갱신"),
        ("MS-DEMO", "한이음 드림업 최종 통합 시연", ["MS-02"], "PLANNED", "통제환경에서 대표 위험안내·길안내·신고 흐름과 제한사항 시연", "2026-07-26", None, "사용자 확정일; 출시 승인이 아님"),
        ("MS-03", "CB/RB/DB 사람 검토·승인", ["MS-02"], "PLANNED", "관리·요구·설계의 사람 승인과 manifest", None, None, "문서 완성도와 검토자 가용성에 따라 결정"),
        ("MS-04", "구현 정렬 build", ["MS-03"], "PLANNED", "요구·설계와 build 형상 연결", None, None, "구현 Gap 분석 뒤 결정"),
        ("MS-05", "정식 시험 실행", ["MS-04"], "PLANNED", "필수 case·결함·STR·잔여위험 snapshot", None, None, "시험계획 승인 뒤 결정"),
        ("MS-06", "gate·교차 입력 종결", ["MS-04"], "PLANNED", "5 gate와 SEC/REL/WS 선행조건 완료", None, None, "외부 검토·실측 가용성에 따라 결정"),
        ("MS-07", "출시 준비도·인수", ["MS-05", "MS-06"], "BLOCKED", "TST-22 Go와 TST-23 서명", None, None, "모든 차단조건 종결 뒤에만 지정"),
    ]
    return {
        "schema_version": "walksafe.schedule.v1",
        "metadata": _metadata(["MGT-07"], "WalkSafe 마일스톤 계획"),
        "date_policy": "사용자가 확정한 2026-07-26 최종 시연과 실제 승인일만 날짜로 기록한다. 나머지는 선행조건과 완료 조건(exit condition)으로 통제하고 근거가 생기면 김민호가 날짜를 승인한다.",
        "schedule_owner": {"role": "프로젝트관리자", "person": "김민호"},
        "critical_path": ["MS-01", "MS-02", "MS-03", "MS-04", "MS-05", "MS-07"],
        "buffer_policy": "1인 프로젝트이므로 고정 여유시간을 꾸미지 않는다. 안전·외부검토 작업은 시연 범위에서 분리하고 지연 시 출시 차단을 유지한다.",
        "milestones": [
            {"milestone_id": item[0], "name": item[1], "depends_on": item[2], "status": item[3], "exit_condition": item[4], "target_date": item[5], "actual_date": item[6], "date_basis": item[7]}
            for item in milestones
        ],
    }


def _stakeholders() -> dict[str, Any]:
    rows = [
        ("STK-01", "시각장애 사용자", "HIGH", "핵심 사용자·사용성/안전 의견", "조사·시험 때 동의·안전계획 아래 참여", "시험 전 동의·안전보호 필요", None, None),
        ("STK-02", "프로젝트·제품책임자", "HIGH", "정책·범위·변경·출시 최종 결정", "매 작업일 문서·위험·결정 확인", "정식 산출물별 승인 기록 필요", "김민호", "최종 결정"),
        ("STK-03", "개발·기술책임자", "HIGH", "Android·서버·모델·통합 구현", "요구·설계·build 변경 때 참여", "1인 역할 겸임을 기록", "김민호", "내부 기술 실행"),
        ("STK-04", "QA·접근성·안전 검토자", "HIGH", "시험 설계·독립 검토", "계획 검토와 증거 생성 때 참여", "독립 역할 미배정", None, "시험 중단 권고"),
        ("STK-05", "보안·개인정보 검토자", "HIGH", "원본수집·인증·삭제·사고 검토", "고위험 설계·출시 전 참여", "독립 출시 검토 미실행", None, "출시 차단 의견"),
        ("STK-06", "서비스 관리자", "HIGH", "신고·상태·백업·복구·비용 운영", "평일 09:00~18:00 대응", "단일 관리자 복구훈련 미실행", "김민호", "운영 중지·복구"),
        ("STK-07", "한이음 멘토·평가/인수 주체", "MEDIUM", "프로젝트 지도·산출물 검토·외부 인수", "마일스톤·시연·인수 때 참여", "역할·서명 범위 확인 필요", None, "외부 검토·인수"),
        ("STK-08", "TMAP·Google Cloud 제공자", "MEDIUM", "경로·서울리전 저장·외부 서비스", "연동·쿼터·장애·비용 검토 때 참여", "실제 계약·쿼터·비용 확인 필요", None, "서비스 계약 범위"),
        ("STK-09", "공공기관 신고 접수자", "MEDIUM", "승인된 손상 점자블록 신고 수령", "기관 전달 절차를 합의할 때 참여", "기관별 채널·접수 증거 확인 필요", None, "수신·처리 범위"),
    ]
    return {
        "schema_version": "walksafe.stakeholder-register.v1",
        "metadata": _metadata(["MGT-09"], "WalkSafe 이해관계자 목록"),
        "stakeholders": [
            {
                "stakeholder_id": row[0],
                "group": row[1],
                "influence": row[2],
                "interest_or_role": row[3],
                "engagement": row[4],
                "current_gap": row[5],
                "assigned_person": row[6],
                "authority": row[7],
                "delegate": None,
            }
            for row in rows
        ],
        "review_rule": "담당자·권한·참여 방식이 바뀌면 즉시 갱신하고 매주 미배정 독립 역할을 확인한다.",
    }


def _raci() -> dict[str, Any]:
    rows = [
        ("정책·범위 변경", "제품책임자", "김민호", "프로젝트책임자", "김민호", ["기술", "QA", "보안·개인정보"], ["이해관계자"]),
        ("요구·인수조건", "요구사항책임자", "김민호", "제품책임자", "김민호", ["기술", "QA", "접근성·안전"], ["개발"]),
        ("설계", "기술책임자", "김민호", "제품책임자", "김민호", ["보안·개인정보", "운영", "접근성·안전"], ["QA"]),
        ("구현·build", "개발책임자", "김민호", "기술책임자", "김민호", ["QA", "보안"], ["제품책임자"]),
        ("시험계획·결함관리", "QA책임자", "김민호", "제품책임자", "김민호", ["기술", "접근성·안전"], ["개발"]),
        ("독립 개인정보·현장안전 검토", "독립 검토자", None, "제품책임자", "김민호", ["보안·개인정보", "접근성·안전"], ["프로젝트"]),
        ("출시 준비도", "QA책임자", "김민호", "프로젝트책임자", "김민호", ["제품", "기술", "보안·개인정보", "운영"], ["인수자"]),
        ("인수", "지정 인수자", None, "지정 인수자", None, ["프로젝트책임자", "QA"], ["이해관계자"]),
    ]
    return {
        "schema_version": "walksafe.raci.v1",
        "metadata": _metadata(["MGT-10"], "WalkSafe RACI"),
        "assignment_status": "INTERNAL_ROLES_ASSIGNED_TO_SINGLE_OWNER_EXTERNAL_INDEPENDENT_ROLES_OPEN",
        "single_owner_rule": "김민호가 내부 역할을 겸임한다. 독립 검토·외부 인수는 존재하지 않는 사람을 배정하지 않고 미배정 상태로 유지한다.",
        "rows": [
            {"work": row[0], "responsible_role": row[1], "responsible_person": row[2], "accountable_role": row[3], "accountable_person": row[4], "consulted": row[5], "informed": row[6], "delegate": None}
            for row in rows
        ],
    }


def _pmp(policy: dict[str, Any]) -> str:
    return _header(
        "WalkSafe 프로젝트 관리계획",
        MGT_COVERAGE["project-management-plan.md"],
        "누가 어떤 순서와 품질·변경 규칙으로 산출물을 만들고 검토하며 기준선을 세우는가?",
    ) + f"""

<a id="mgt-05"></a>
## MGT-05 프로젝트 관리계획

작업은 정책→관리/제품→요구/인수조건→시험계획→설계→구현정렬→시험실행→출시판정 순서로 진행합니다. 김민호가 프로젝트관리자·제품책임자·내부 실무자를 겸임합니다. 각 산출물은 Draft, In Review, Approved, Baselined를 건너뛰지 않으며 AI 생성 성공은 사람 승인이 아닙니다.

| 관리영역 | 운영방법 | 통제 원장·판정 |
|---|---|---|
| 범위 | MGT-04·DSC-12를 기준으로 변경요청의 영향분석 뒤 변경 | MGT-16 |
| 일정 | 7월 26일 통제 시연은 고정, 출시는 gate 기반으로 별도 계획 | MGT-07·17 |
| 자원·비용 | 별도 배정 예산 없음, 지출 전 김민호 승인, 저장비 월 3만원 상한 | MGT-08·OPS-22 |
| 품질 | 추적성·재현성·안전차단·접근성·개인정보 gate 적용 | MGT-12·TST-01 |
| 위험 | 원인·영향·대응·잔여위험을 OPEN부터 종결까지 관리 | MGT-14 |
| 변경·형상 | 승인본 불변, 새 버전·지문·supersedes로 대체 | MGT-13·16 |
| 소통 | 저장소 정본과 의사결정 기록을 중심으로 보고 | MGT-11·15·18 |

단계 진입은 앞 단계 완료기준과 차단 위험 확인 뒤 김민호가 기록으로 승인합니다. 실제 사용자·독립 검토·외부 인수 증거가 필요한 단계는 1인 자가승인으로 대체하지 않습니다.

<a id="mgt-06"></a>
## MGT-06 WBS

[registers/wbs.json](registers/wbs.json)에 8개 작업 묶음과 담당자 김민호, 선행관계, 상대공수, MVP 구분, 연결 마일스톤과 각 작업을 끝냈다고 판단하는 완료 조건(exit condition)을 기록했습니다. 완료율은 파일 수가 아니라 승인·검증 조건으로 계산합니다. 상대공수는 시간 실적이 아니며 요구·설계 기준선 뒤 다시 추정합니다.

<a id="mgt-07"></a>
## MGT-07 일정·마일스톤

[registers/schedule.json](registers/schedule.json)은 사용자가 확정한 **2026년 7월 26일 최종 통합 시연**과 실제 승인일만 날짜로 기록하고, 나머지는 선행조건과 완료조건을 사용합니다. 시연은 대표 흐름을 통제환경에서 보여 주는 마일스톤이며 정식 출시·현장안전 합격을 뜻하지 않습니다. 현재 MS-02는 진행 중이고 출시 마일스톤은 차단 상태입니다.

<a id="mgt-08"></a>
## MGT-08 예산·자원 계획

- 적용성: `ACTIVE` — Google Cloud·TMAP·문자 등 유료 가능 자원과 실기기를 계획하므로 조건부 MGT-08이 활성화됩니다.
- 별도로 배정·승인된 프로젝트 총예산: **없음**. 이 사실을 0원 집행 한도나 무제한 지출 허용으로 해석하지 않습니다.
- 현재 인력: 김민호 1인이 프로젝트·제품·내부 개발·평일 운영 역할을 겸임합니다. 전문 독립 검토자·안전요원·외부 인수자는 미배정입니다.
- 저장 자원 정책: Google Cloud 서울 리전 주 원본 300GiB+백업 300GiB, 저장비 월 30,000원 상한. 실제 비용 충족은 `GATE-CLOUD-COST-MEASUREMENT`에서 확인합니다.
- 보유·확인 자원: 기준 후보 Android 실기기(컴퓨터 연결 기종과 Galaxy S25), 개발환경. 정확한 모델·OS·거리기능은 TST-03에서 확인합니다.
- 필요한 외부 자원: 안전요원, 대상 사용자, 접근성/보안/개인정보 독립 검토, PostGIS·원본 저장소·TMAP 시험환경.
- 집행 규칙: 새 유료계약·cloud 생성·운영 배포는 사전 비용추정과 영향분석 뒤 김민호가 명시적으로 승인해야 합니다. 월 3만원은 저장비 상한이며 TMAP·문자·모니터링 등 다른 비용은 지출 전 별도 승인합니다.
- 초과 처리: 예상 또는 실제 비용이 승인값을 넘으면 새 수집·외부자원 확대를 보류하고 MGT-14·16에 원인·대안·승인 결과를 남깁니다. 만료 전 원본을 비용 때문에 임의 삭제하지 않습니다.

<a id="mgt-09"></a>
## MGT-09 이해관계자

[registers/stakeholders.json](registers/stakeholders.json)에 사용자·책임자·검토자·외부 제공자·인수 주체의 영향력, 권한, 참여 방식, 담당자와 대리인 공백을 등록했습니다. 김민호는 승인된 내부 역할에 배정했고 실제 독립 검토자·인수자는 이름을 만들지 않았습니다.

<a id="mgt-10"></a>
## MGT-10 RACI

[registers/raci.json](registers/raci.json)은 김민호가 내부 Responsible·Accountable 역할을 겸임함을 명시합니다. 개인정보·원본수집 독립검토, 현장시험 동의, 최종 외부 인수는 미배정으로 남기며 필요한 독립성을 없애지 않습니다. 김민호 부재 시 임의 대리하지 않고 고위험 작업을 동결합니다.

<a id="mgt-11"></a>
## MGT-11 의사소통·보고 계획

| 사건 | 기록 | 통보 대상 | 시점 |
|---|---|---|---|
| 정책·범위 변경 | CR·영향분석·결정 | 제품/기술/QA/보안 | 구현 전 |
| 작업일 진행 | dashboard·action | 김민호 | 활성 작업일 종료 전 |
| 주간 종합 | dashboard·RAID·schedule·backlog | 김민호, 참여 중인 멘토·검토자 | 매주 또는 마일스톤 전 |
| S1/S2 결함·사고 | 결함/incident·안전정지 | 책임자·관련 검토자 | 즉시 |
| gate 측정·검토 | 실행 evidence·결정 | 제품/QA/출시 승인자 | TST-22 전 |
| 기준선·출시·인수 | 파일 목록·지문 기록(manifest)·인수 확인서(receipt) | 지정 승인·인수자 | 효력 전 |

- 공식 정본은 `docs/deliverables`, 통제·승인 기록은 `docs/control`, 코드·시험 증거는 해당 통제 경로에 저장합니다.
- 채팅·회의는 입력일 뿐 결정 정본이 아닙니다. 결정·action·변경요청으로 옮겨 ID와 근거를 남겨야 효력이 있습니다.
- 원본 영상·음성·정확 위치·전화번호·인증정보·비밀값은 일반 채팅·회의록·공개 issue에 넣지 않습니다. 승인 저장소와 최소권한 경로만 사용합니다.
- 사고·S1/S2 결함은 평일 09:00~18:00에 김민호가 대응합니다. 승인된 시간외 대응자가 없으면 지원시간 밖 새 보행을 차단합니다.

<a id="mgt-12"></a>
## MGT-12 품질관리 계획

- DOC-01부터 CLS-16까지 257개 산출물 유형은 통제대장에 정확히 한 번 등록하고 통합문서 anchor를 둡니다.
- 요구는 정책·결정·인수조건·시험 ID와 연결합니다.
- 존재하지 않는 설계·코드·시험 증거를 만들지 않습니다.
- skip·mock·부분성공·다른 build 결과를 PASS로 승격하지 않습니다.
- Android 정식 제품과 Web/PWA legacy 경계를 자동 검사합니다.
- 승인된 기준선이나 외부 의무가 독립·전문 검토를 명시한 산출물과 gate만 해당 검토가 끝날 때까지 `In Review` 또는 `Planned`에 둡니다. 그 밖의 문서는 1인 프로젝트의 제품책임자가 최종 승인할 수 있으며 존재하지 않는 별도 검토자를 만들지 않습니다.

| 등급 | 예시 | 처리·출시 영향 |
|---|---|---|
| S1 | 다칠 가능성을 만드는 안내, 민감정보 노출, 권한 우회, 복구불가 자료유실 | 즉시 안전정지·증거보존, 원인·완화·재시험 전 관련 작업과 출시 차단 |
| S2 | 필수 흐름 접근 불가, 반복 충돌·주요 기능 오판 | 다음 후보 build 전 수정·회귀시험, 미해결이면 출시 차단 |
| S3 | 우회 가능한 기능 결함·문서 불일치 | 담당·기한을 정해 Backlog·결함원장에서 추적 |
| S4 | 표현·편의 개선 | 범위·위험에 따라 계획 |

품질지표는 정책·요구·시험 추적 누락 0, 5개 gate 5/5·면제 0, 출시 차단 S1/S2 미해결 0, 동일 build·기기·환경 증거 결속을 포함합니다. 예외는 MGT-16·SEC-16에 대안·기간·잔여위험·철회조건과 김민호 승인을 남기며 안전·법률 gate 자체는 면제하지 않습니다.

<a id="mgt-13"></a>
## MGT-13 형상관리 계획

- 정본은 Markdown/JSON, HTML은 생성 view로 관리합니다.
- 정책 PB, 관리 CB, 요구 RB, 설계 DB, build BB, 검증 VB를 별도 파일 목록·지문 기록(manifest)으로 고정합니다.
- manifest(파일 목록과 지문 기록)는 정확한 파일 위치, SHA-256(파일이 바뀌었는지 확인하는 지문), 입력 기준선과 승인 기록을 하나로 연결합니다.
- 승인·기준선 파일을 덮어쓰지 않고 새 revision/version과 supersedes 링크를 만듭니다.
- 코드 버전(source commit), Android 설치파일·서버 실행 묶음(APK/container), 모델, 설정, DB 구조 변경(migration), 환경과 증거를 같은 출시 후보 묶음(generation)으로 관리합니다.

| 형상항목 | 내부 소유자 | 기준선·복구 규칙 |
|---|---|---|
| 정책·결정·MGT/DSC/REQ/DES | 김민호 | 승인문·manifest·SHA-256으로 고정, 새 버전으로만 대체 |
| Android 사용자/관리자 앱·서버 | 김민호 | source commit·build ID·서명·설정을 함께 결속 |
| DB migration·데이터 사전 | 김민호 | 순서·backup·검사·되돌림 가능 여부를 release와 결속 |
| 모델·threshold·label schema | 김민호 | registry·파일지문·평가·배포승인을 결속 |
| 시험·현장·외부 검토 증거 | 지정 실행·검토자 | 원본 증거는 불변 보존, 정정은 새 기록과 사유로 추가 |

기준선 생성 전 대상 파일집합을 동결하고 생성기 `--check`, 관련 시험, 링크·지문 검사를 수행합니다. 실패하면 승인사건을 만들지 않습니다. 복구는 마지막 승인본을 원본 그대로 되살리는 것이 아니라, 호환성과 자료손실 방지를 검증한 절차로 새 변경사건을 남겨 수행합니다.

## 5개 gate

| ID | 내용 | 상태 | 완료조건 |
|---|---|---|---|
{_gate_rows(policy)}

## 현재 승인 경계

이 계획은 Draft입니다. 내부 1인 역할, 7월 26일 시연, 별도 예산 없음, 저장비 상한은 반영됐습니다. 독립 검토자·대상사용자·실행 일정과 실제 비용·시험 결과는 아직 없으며 출시는 NOT_ELIGIBLE입니다.
""" + "\n" + _artifact_management_section(MGT_COVERAGE["project-management-plan.md"], PMP_PATH)


def _raid(policy: dict[str, Any]) -> dict[str, Any]:
    rows = [
        {
            "raid_id": f"RAID-{index:03d}",
            "kind": "RISK",
            "source_gate_id": gate["id"],
            "title": gate["title"],
            "description": gate["closure"],
            "cause": "정책 방법은 확정됐으나 필요한 실측·설계검토·독립검토 또는 훈련이 아직 실행되지 않음",
            "impact": "연결된 통합시험·사용자시험 또는 출시 준비도 승인을 진행할 수 없음",
            "likelihood": "CURRENT",
            "severity": "HIGH",
            "status": "OPEN",
            "owner_role": "관련 전문책임자",
            "assigned_person": None,
            "due_condition": "연결된 사용자시험·통합시험·TST-22 전",
            "response_strategy": "MITIGATE_AND_CLOSE_WITH_EVIDENCE",
            "mitigation": gate["closure"],
            "residual_exposure": "증거가 승인되기 전 관련 출시·시험 차단 유지",
            "waived": False,
        }
        for index, gate in enumerate(policy["remaining_gates"], start=1)
    ]
    rows.extend(
        [
            {"raid_id": "RAID-006", "kind": "DEPENDENCY", "source_gate_id": "WS-21", "title": "현장시험 참여자 동의·안전계획", "description": "현장·대상사용자 시험 전에 승인된 안전계획과 외부 동의 원본이 필요하다.", "status": "OPEN", "owner_role": "접근성·안전책임자", "due_condition": "현장시험 전", "waived": False},
            {"raid_id": "RAID-007", "kind": "DEPENDENCY", "source_gate_id": "SEC-01/04", "title": "보안 개발계획·설계검토", "description": "고위험 구현 진입 전에 보안 계획과 설계검토가 필요하다.", "status": "OPEN", "owner_role": "보안책임자", "due_condition": "DEV-01 정식 구현 승인 전", "waived": False},
            {"raid_id": "RAID-008", "kind": "DEPENDENCY", "source_gate_id": "REL-15", "title": "이전 버전으로 되돌리는 절차(rollback)", "description": "TST-17과 단계적 배포 전에 승인된 이전 버전 복구 절차가 필요하다.", "status": "OPEN", "owner_role": "릴리스책임자", "due_condition": "TST-17 전", "waived": False},
            {"raid_id": "RAID-009", "kind": "DEPENDENCY", "source_gate_id": "REL-01/02,SEC-14,WS-20", "title": "출시 준비도 외부 입력", "description": "릴리스 계획·체크리스트, 활성 침투시험, 실제 휴대폰 E2E가 필요하다.", "status": "OPEN", "owner_role": "프로젝트책임자", "due_condition": "TST-22 전", "waived": False},
            {
                "raid_id": "RAID-010",
                "kind": "ISSUE",
                "source_gate_id": "HIST-ARTIFACT-CATALOG-SNAPSHOT",
                "title": "2026-07-18 중간 질문지의 산출물 목록 원본 스냅샷 부재",
                "description": "7월 18일 답변과 당시 결정 원장은 산출물 목록 지문 dcd998...에 결속되어 있으나 그 정확한 파일 사본이 남아 있지 않다. 따라서 해당 중간 질문지를 현재 목록으로 다시 만들거나 과거 답변을 새 목록에 재결속하지 않는다. 현재 승인 정책과 0~6 Draft는 각각 승인 지문과 현재 목록 지문으로 따로 검증한다.",
                "status": "OPEN",
                "owner_role": "문서통제담당",
                "due_condition": "다음 정책·산출물 기준선 전에 향후 snapshot 보존 절차 확인",
                "waived": False,
                "historical_catalog_sha256": HISTORICAL_ARTIFACT_CATALOG_SHA256,
                "current_catalog_sha256": _sha_file(CATALOG_PATH),
                "current_baseline_impact": "NO_CURRENT_TRACE_BREAK_FOUND",
                "recovery_rule": "과거 파일을 추정 복원하지 않고, 재질문이 필요하면 새 revision과 새 검토·승인을 만든다.",
            },
            {
                "raid_id": "RAID-011",
                "kind": "ISSUE",
                "source_gate_id": FP035_NETWORK_ISSUE_ID,
                "title": "FP-035 일반 활동원본의 이동통신망 전송 조건 문구 충돌",
                "description": "승인 기준선의 시작조건·실행경계는 사용자가 명시적으로 선택한 이동통신망 전송을 허용하지만, 같은 기능의 요약·정상흐름·설계규칙 일부는 Wi-Fi를 필수로 적었다. 두 문구 중 하나를 임의로 선택해 구현하지 않는다.",
                "status": "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
                "owner_role": "제품책임자",
                "due_condition": "RQ-FP-035-001·관련 설계 승인 또는 해당 전송 구현 전",
                "waived": False,
                "affected_policy_ids": ["FP-035", "SP-13", "CD-UPLOAD-NETWORK"],
                "affected_requirement_ids": ["RQ-FP-035-001"],
                "direct_artifact_codes": FP035_DIRECT_ARTIFACT_CODES,
                "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
                "correction_candidate_path": _rel(FP035_CORRECTION_PATH),
                "correction_candidate_sha256": _sha_file(FP035_CORRECTION_PATH),
                "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
                "captured_owner_directive": "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다.",
                "approval_status": "NOT_APPROVED_AS_NEW_ARTIFACT_OR_POLICY_BASELINE",
                "safe_interim_rule": "지시사항을 반영한 산출물 묶음이 새로 승인되기 전에는 일반 활동원본의 이동통신망 전송 구현·실행 동결을 유지한다.",
                "resolution_rule": "포착한 지시문, 영향분석, 정정된 요구·설계와 새 일괄 승인 결속을 완료한다.",
            },
        ]
    )
    for row in rows:
        row.setdefault("cause", "필요한 선행 결정·계획·외부 증거가 아직 종결되지 않음")
        row.setdefault("impact", "연결 산출물 또는 후속 단계의 승인·실행이 차단될 수 있음")
        row.setdefault("likelihood", "CURRENT")
        row.setdefault("severity", "HIGH")
        row.setdefault("assigned_person", None)
        row.setdefault("response_strategy", "MITIGATE")
        row.setdefault("mitigation", row["description"])
        row.setdefault("residual_exposure", "종결 증거 승인 전 OPEN 상태 유지")
    return {
        "schema_version": "walksafe.raid-register.v1",
        "metadata": _metadata(["MGT-14"], "WalkSafe RAID"),
        "rows": rows,
        "summary": {"total": len(rows), "open": len(rows), "gate_count": 5, "waived_gate_count": 0},
    }


def _decision_pointer(decisions: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.management-decision-pointer.v1",
        "metadata": _metadata(["MGT-15"], "WalkSafe 의사결정 기록"),
        "canonical_aligned_register": {
            **_binding(ALIGNED_DECISIONS_PATH),
            "register_id": decisions["metadata"]["register_id"],
            "register_version": decisions["metadata"]["register_version"],
            "lifecycle_status": decisions["metadata"]["lifecycle_status"],
            "artifact_approval_status": decisions["approval_boundary"]["artifact_approval_status"],
            "decision_count": decisions["coverage"]["actual_decision_count"],
            "decision_feature_edge_count": decisions["coverage"]["decision_feature_edge_count"],
        },
        "copy_policy": "135개 결정을 복제하지 않고 승인 정책을 참조하는 정렬 원장을 정본 후보로 연결한다.",
        "required_decision_fields": ["결정 ID", "질문·배경", "선택한 결정", "대안", "근거", "영향 산출물", "결정권자", "효력일", "재검토 조건", "대체 결정"],
        "governance": {
            "owner": "김민호",
            "append_rule": "새 결정이나 정정은 새 ID·버전으로 추가하고 이전 결정은 superseded_by로 연결",
            "review_cycle": "정책·범위·gate·외부조건 변경 때 즉시, 매 마일스톤 전 검토",
        },
        "approval_boundary": {"formal_register_approved": False, "release_status": RELEASE_STATUS},
    }


def _change_requests() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.change-request-register.v1",
        "metadata": _metadata(["MGT-16"], "WalkSafe 변경요청·변경이력"),
        "requests": [
            {
                "change_request_id": "CR-0001",
                "title": "정식 산출물의 Android 주제품 경계 재정렬",
                "trigger": "승인 정책 FP-007~009와 준비문서의 Web/PWA 전제가 불일치",
                "scope": ["artifact-types.json", "documentation-authoring-preparation-plan.md", "0~6 Draft"],
                "policy_change": False,
                "implementation_change": False,
                "status": "APPLIED_TO_DRAFT_PENDING_REVIEW",
                "approval_status": "NOT_APPROVED_AS_FORMAL_CHANGE_RECORD",
                "before": "Web/PWA를 현재 제품처럼 읽을 수 있는 오염된 문서 전제",
                "after_proposed": "Android 사용자·비공개 관리자 앱은 정식 제품, Web/PWA는 legacy",
                "impact_analysis": ["MGT·DSC·REQ·DES·TST 문서 경계 변경", "현재 구현 적합성은 별도 Gap 분석", "정책 내용 변경 없음"],
                "implementation_commit": None,
                "verification_evidence": "생성기 구조검사와 관련 단위시험; 사람 검토 대기",
                "rollback_or_freeze": "검토 반려 시 Draft revision으로 정정하고 승인본은 생성하지 않음",
                "result": "Android 사용자·관리자 앱은 정식 제품, Web/PWA는 legacy로 문서 초안을 정렬했다.",
            },
            {
                "change_request_id": "CR-0002",
                "title": "FP-035 일반 활동원본 전송망 조건 문구 정합화",
                "trigger": "같은 승인 정책 안에 'Wi-Fi 또는 명시적 이동통신망 선택'과 'Wi-Fi 필수' 문구가 함께 존재",
                "scope": [
                    "FP-035",
                    "SP-13",
                    "RQ-FP-035-001",
                    *FP035_DIRECT_ARTIFACT_CODES,
                    "MOD-ANDROID-USER",
                    "MOD-BACKEND",
                    "TC-FP-035-*",
                ],
                "direct_artifact_codes": FP035_DIRECT_ARTIFACT_CODES,
                "related_downstream_artifact_codes": [
                    "REQ-16",
                    "REQ-18",
                    *FP035_RELATED_DOWNSTREAM_IDS,
                    "MGT-14",
                    "MGT-16",
                    "DEV-18",
                    "TST-05",
                ],
                "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
                "correction_candidate_path": _rel(FP035_CORRECTION_PATH),
                "correction_candidate_sha256": _sha_file(FP035_CORRECTION_PATH),
                "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
                "policy_change": True,
                "implementation_change": False,
                "status": "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
                "approval_status": "NOT_APPROVED",
                "result": None,
                "before": "FP-035 안에 이동통신망 선택 허용 문구와 Wi-Fi 필수 문구가 함께 존재",
                "captured_owner_directive": "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다.",
                "after_proposed": "포착한 지시문으로 FP-035·SP-13·요구·설계 문구를 통일",
                "impact_analysis": ["보행 중 전송 금지 유지", "이동통신망 opt-in 상태와 허용망 검증 필요", "미선택 사용자는 Wi-Fi 전용", "관련 요구·설계·시험·동의 문구 재결속 필요"],
                "verification_evidence": None,
                "implementation_commit": None,
                "rollback_or_freeze": "새 일괄 승인 전 일반 활동원본 이동통신망 전송 구현·실행 동결",
                "interim_rule": "지시사항을 반영한 산출물 묶음이 새로 승인되기 전에는 일반 활동원본의 이동통신망 전송 구현과 시험을 시작하지 않는다.",
            },
        ],
    }


def _actions() -> dict[str, Any]:
    actions = [
        ("ACT-001", "0~6 Draft 128개 유형의 정본 경로·장 위치 확인", "문서통제담당", "AUTOMATED_CHECK_IN_PROGRESS", "사람 검토 전"),
        ("ACT-002", "14개 조건부 적용성 판정", "프로젝트책임자", "OPEN", "각 bundle 승인 전"),
        ("ACT-003", "관리·요구·설계·시험계획 검토자 배정", "프로젝트책임자", "OPEN", "In Review 전"),
        ("ACT-004", "현재 구현 후보의 재사용·수정·폐기·legacy 판정", "기술책임자", "OPEN", "build 기준선 전"),
        ("ACT-005", "5개 gate protocol 승인·실행", "관련 전문책임자", "OPEN", "연결된 차단 단계 전"),
        ("ACT-006", "SEC/WS/REL 외부 선행 산출물 개설", "프로젝트책임자", "OPEN", "시험·출시 gate 전"),
        ("ACT-007", "앞으로 기준선마다 입력 산출물 목록의 정확한 파일 사본 보존", "문서통제담당", "OPEN", "다음 정책·산출물 기준선 전"),
        ("ACT-008", "포착된 FP-035 전송망 지시를 요구·설계·시험에 일관되게 반영하고 새 산출물 묶음으로 승인", "제품책임자", "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL", "RQ-FP-035-001·관련 설계 승인 또는 구현 전"),
    ]
    return {
        "schema_version": "walksafe.action-register.v1",
        "metadata": _metadata(["MGT-18"], "WalkSafe Action Item"),
        "meeting_record_policy": {
            "actual_meeting_records_created": 0,
            "required_fields": ["회의·대화 ID", "일시", "참여자", "논의", "결정 ID", "action ID", "담당자", "기한", "이월 또는 종결 근거"],
            "carry_forward_rule": "미완료 action은 삭제하지 않고 다음 검토의 carried_from에 연결",
        },
        "actions": [
            {"action_id": row[0], "action": row[1], "owner_role": row[2], "status": row[3], "due_condition": row[4], "assigned_person": "김민호" if row[2] in {"프로젝트책임자", "제품책임자", "문서통제담당", "기술책임자"} else None, "opened_at": AS_OF, "completed_at": None, "carried_from": None}
            for row in actions
        ],
    }


def _dashboard() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.project-dashboard.v1",
        "metadata": _metadata(["MGT-17"], "WalkSafe 진행상태 대시보드"),
        "observed_at": f"{AS_OF}T00:00:00+09:00",
        "observation_precision": "DATE_BOUNDARY_NOT_REAL_TIME",
        "status": {
            "policy_baseline": "BASELINED",
            "policy_items_confirmed": 63,
            "aligned_decisions": 135,
            "formal_0_to_6_type_count": 128,
            "formal_draft_generation": "DRAFT_FILES_GENERATED_REVIEW_PENDING",
            "formal_approvals": 0,
            "remaining_gates_not_run": 5,
            "remaining_gates_waived": 0,
            "formal_test_executions": 0,
            "open_policy_correction_count": 1,
            "open_policy_correction_ids": [FP035_NETWORK_ISSUE_ID],
            "fp035_correction_status": "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
            "release_status": RELEASE_STATUS,
        },
        "milestones": {
            "policy_baseline": "COMPLETE_2026-07-21",
            "formal_drafts": "IN_PROGRESS",
            "final_demo": "PLANNED_2026-07-26",
            "release_readiness": "BLOCKED",
        },
        "p0_open_items": [FP035_NETWORK_ISSUE_ID, *[f"GATE-{name}" for name in ["PHONE-QUEUE-BYTE-LIMIT", "SERVER-CAPACITY-STATE-CONTRACT", "RAW-COLLECTION-RELEASE-REVIEW", "CLOUD-COST-MEASUREMENT", "SINGLE-ADMIN-RECOVERY-DRILL"]]],
        "coverage": {"policy_items": "63/63", "aligned_decisions": "135/135", "formal_0_to_6_types": "128/128 paths generated; content review in progress"},
        "trend": {"previous_observation": None, "delta": "INITIAL_CONTROLLED_OBSERVATION", "claim": "추세 비교를 위한 이전 동일 정의 관측값 없음"},
        "interpretation": "정책 기준선은 승인됐고 0~6 Draft를 만들었지만 정식 승인·구현·시험·출시는 완료되지 않았다. FP-035 전송망 방향은 사용자 지시로 포착됐으며 요구·설계 정합화와 새 일괄 승인을 기다린다. 그 전까지 이동통신망 전송 동결을 유지한다.",
    }


def _control_document(policy: dict[str, Any], decisions: dict[str, Any]) -> str:
    return _header(
        "WalkSafe 관리 원장·대시보드",
        MGT_COVERAGE["project-control-registers.md"],
        "위험·결정·변경·진행·후속조치를 어느 원장에서 지속 관리하는가?",
    ) + f"""

<a id="mgt-14"></a>
## MGT-14 RAID

[registers/raid.json](registers/raid.json)에 5개 미실행 gate, WS·SEC·REL 교차 의존성, 7월 18일 중간 질문지의 과거 산출물 목록 원본 부재와 FP-035 전송망 정정 항목을 등록했습니다. 각 행에는 원인·영향·가능성·심각도·대응·잔여노출·담당·기한을 둡니다. FP-035 방향은 이미 사용자 지시로 포착됐지만 새 산출물 묶음 승인 전이므로 이동통신망 전송 구현·시험 동결을 유지합니다. gate waiver는 0건입니다.

<a id="mgt-15"></a>
## MGT-15 의사결정 기록

[registers/decisions.json](registers/decisions.json)은 135개 결정·428개 기능 연결을 가진 [승인 정책 정렬 원장](../../control/decision-interview/walksafe-effective-decision-register-aligned-20260721-r001.json)을 경로·SHA-256으로 참조합니다. 이 정렬 원장 자체는 In Review·미승인이고 기존 역사 원장을 덮어쓰지 않습니다.

<a id="mgt-16"></a>
## MGT-16 변경요청·변경이력

[registers/change-requests.json](registers/change-requests.json)에 Android 정식 제품/Web legacy 문서 재정렬을 CR-0001로, FP-035 전송망 문구 정합화를 CR-0002로 남겼습니다. CR-0002에는 “보행 중 미전송, 이동통신망 명시 선택 시 정지 뒤 허용망 전송, 미선택 시 Wi-Fi만”이라는 기존 사용자 지시를 기록했습니다. 상태는 `OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL`이며 새 승인사건을 만들지는 않았습니다.

<a id="mgt-17"></a>
## MGT-17 진행상태 대시보드

[registers/dashboard.json](registers/dashboard.json)은 정책 63개 확정, 결정 135개 정렬, 0~6 초안 파일 생성, 정식 승인 0개, FP-035 지시 포착·묶음승인 대기 1개, 필수 검증 5개 미실행, 출시 불가 상태를 관측시각과 함께 보여 줍니다.

<a id="mgt-18"></a>
## MGT-18 회의 결정·Action Item

[registers/actions.json](registers/actions.json)에 검토자 배정, 조건부 판정, 구현정렬, gate 실행, 교차 산출물 개설, 앞으로의 입력 snapshot 보존과 FP-035 묶음승인을 남겼습니다. 실제 회의가 없었으므로 회의록이나 완료시각을 만들지 않으며, 다음 회의·대화 결정은 날짜·참여자·결정·근거·action을 새 행으로 추가합니다.

## 현재 gate

| ID | 내용 | 상태 | 완료조건 |
|---|---|---|---|
{_gate_rows(policy)}

## 현재 승인 경계

- 원장 구조: Draft/구조검사
- 135개 정렬 원장: In Review, formal approval 없음
- gate: 5개 NOT_RUN, 미면제
- 출시: NOT_ELIGIBLE
""" + "\n" + _artifact_management_section(MGT_COVERAGE["project-control-registers.md"], CONTROL_REGISTER_PATH)


def _discovery_evidence(policy: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.discovery-evidence-register.v1",
        "metadata": _metadata([f"DSC-{number:02d}" for number in range(1, 10)], "WalkSafe 발견 근거 원장"),
        "evidence": [
            {
                "evidence_id": "DSC-EVD-001",
                "kind": "APPROVED_POLICY_BASELINE",
                **_binding(POLICY_MANIFEST_PATH),
                "adoption_trust": "A",
                "allowed_use": "문제·사용자·제품범위·기능정책의 직접 입력",
            },
            {
                "evidence_id": "DSC-EVD-002",
                "kind": "CURRENT_IMPLEMENTATION_CANDIDATE",
                "paths": ["apps/android", "backend", "model", "voice"],
                "adoption_trust": "CANDIDATE",
                "allowed_use": "PoC 가능성·현재 사실 후보; 목표 정책이나 완료 증거로 단독 사용 금지",
            },
            {
                "evidence_id": "DSC-EVD-003",
                "kind": "LEGACY_HISTORY",
                "paths": ["apps/web", "docs/submission"],
                "adoption_trust": "HISTORY",
                "allowed_use": "배경·비교 참고; Android 정식 제품의 완료 근거로 금지",
            },
        ],
        "research_status": {
            "DSC-03": "DRAFT_PLAN_EXECUTION_NOT_RUN",
            "DSC-04": "PLANNED_NOT_RUN_NO_RESEARCH_RESULT_CLAIMED",
            "DSC-05": "DRAFT_POLICY_PROFILE_ONLY_NOT_RESEARCH_PERSONA",
            "DSC-07": "PLANNED_NOT_RUN",
            "DSC-09": "CANDIDATE_EVIDENCE_NOT_REVALIDATED_AFTER_POLICY_BASELINE",
            "participant_count": 0,
            "interview_count": 0,
            "usability_session_count": 0,
        },
        "research_plan": {
            "owner": "김민호",
            "reviewers_required_before_execution": ["접근성·안전책임자", "보안·개인정보책임자"],
            "participant_plan": {"total_target": 6, "blind_minimum": 3, "low_vision_minimum": 3, "smartphone_novice_representation_required": True, "planning_value_not_actual_count": True},
            "phase_1": "실내·폐쇄 통제공간에서 안내문 이해, 화면읽기, 음성명령, 오류·정지, 장착과 동의 이해를 관찰",
            "phase_2": "WS-21 안전계획과 관련 gate 승인 뒤 안전요원 동행 통제 보도 시험",
            "schedule": "문서·시험계획 승인과 참여자 동의 확보 뒤; 확정 날짜 없음",
            "analysis": ["과업 성공률", "도움 요청·오류·안전정지 빈도", "안내 이해도", "주제별 정성 코딩", "전맹·저시력 결과 분리"],
            "execution_status": "NOT_RUN",
        },
        "hypotheses": [
            {"hypothesis_id": "HYP-001", "type": "USABILITY", "statement": "전맹·저시력 사용자가 화면을 계속 보지 않고 핵심 흐름을 수행할 수 있다.", "risk": "필수 흐름 접근 불가", "sample": "계획 6명, 전맹·저시력 각 최소 3명", "pass_fail_rule": "TST-15 사전 승인 protocol에서 확정", "owner": "김민호", "due_condition": "대상 사용자 시험 전", "result": "NOT_RUN"},
            {"hypothesis_id": "HYP-002", "type": "SAFETY", "statement": "가슴·목걸이형 장착과 위험안내가 통제된 보행에서 위험한 행동을 유발하지 않는다.", "risk": "오안내·지연으로 인한 위해", "sample": "지원기기·체형·환경 조합", "pass_fail_rule": "WS-07·08·21 사전 승인 protocol에서 확정", "owner": "김민호", "due_condition": "현장시험 전", "result": "NOT_RUN"},
            {"hypothesis_id": "HYP-003", "type": "OPERATIONS", "statement": "한 명의 관리자가 평일 09~18 범위에서 신고·장애·복구를 통제할 수 있다.", "risk": "관리자 부재·계정분실 때 고위험 작업 불가", "sample": "복구훈련 1회와 운영 시나리오", "pass_fail_rule": "GATE-SINGLE-ADMIN-RECOVERY-DRILL 완료", "owner": "김민호", "due_condition": "실제 사용자시험 또는 배포 전", "result": "NOT_RUN"},
        ],
        "competitor_research": {"status": "NOT_RUN", "comparison_dimensions": ["대상 사용자", "안전 고지", "오프라인", "접근성", "개인정보", "가격"], "result_count": 0},
        "poc": {"status": "NOT_REVALIDATED_AFTER_POLICY_BASELINE", "candidate_paths": ["apps/android", "backend", "model", "voice"], "completion_claimed": False},
    }


def _discovery_document(policy: dict[str, Any]) -> str:
    by_id = {feature["id"]: feature for feature in policy["features"]}
    flow_rows = "\n".join(f"| {flow['id']} | {flow['title']} | {flow['summary']} |" for flow in policy["end_to_end_flows"])
    gate_rows = "\n".join(f"| {gate['id']} | {gate['title']} | {gate['closure']} |" for gate in policy["remaining_gates"])
    implementation_counts: dict[str, int] = {}
    for feature in policy["features"]:
        status = feature["implementation"]["status_before_review"]
        implementation_counts[status] = implementation_counts.get(status, 0) + 1
    implementation_text = ", ".join(f"{key} {value}개" for key, value in sorted(implementation_counts.items()))
    return _header(
        "WalkSafe 발견 근거·분석",
        DSC_COVERAGE["discovery-evidence-and-analysis.md"],
        "어떤 문제와 사용자를 대상으로 하며, 무엇이 확인된 사실이고 무엇은 조사·PoC·실측이 더 필요한가?",
    ) + f"""

<a id="dsc-01"></a>
## DSC-01 문제 정의

{by_id['FP-001']['plain_summary']}

핵심 문제는 사용자가 화면을 계속 보지 않고 가까운 위험·큰 방향·오류와 중단을 알아차려야 한다는 점입니다. 해결책은 안전 보조이며 흰지팡이·안내견 대체나 보행 안전 보장을 주장하지 않습니다.

### 현재 근거와 검증 방법

- 확정 근거: 승인된 제품 목적·사용자·기능정책, Android 주제품 결정, 일반 도심 보도 범위
- 아직 없는 근거: 실제 대상 사용자의 문제 빈도·고충·현재 대처·안내 이해도 조사 결과
- 비교 대안: 기존 보조수단만 사용, 단일 객체탐지 시연, Web/PWA, Android 통합 보행보조. 현재는 Android 통합 보조를 선택했지만 우월성을 입증한 것은 아닙니다.
- 문제 검증: DSC-03 계획에 따라 전맹·저시력 사용자의 현재 여정과 과업 실패를 조사하고, 관찰 근거가 문제 정의를 지지하지 않으면 DSC-01·02·10과 Backlog를 변경통제합니다.

<a id="dsc-02"></a>
## DSC-02 대상 사용자·이해관계자

- 같은 우선순위의 사용자: 전맹 시각장애인과 저시력 시각장애인
- 사용성 원칙: 스마트폰 사용이 익숙하지 않은 사람도 혼자 핵심 기능을 이해할 수 있게 하고, 실제 보행 전 접근 가능한 짧은 교육·연습을 제공
- 공식 환경: 일반 도심 보도와 승인된 조건의 횡단 구간
- 조작 전제: 화면을 계속 확인하지 않고 TalkBack·음성·진동을 사용
- 운영 이해관계자: 단일 관리자, 프로젝트·제품책임자, 안전/접근성/보안 검토자, TMAP·클라우드·신고 기관

| 사용자 집단 | 포함 기준 | 제외·유예 | 환경·빈도 가정 | 보호 필요 |
|---|---|---|---|---|
| 전맹 사용자 | 만 14세 이상, 한국어 Android 음성·화면읽기 사용 가능, 교육·동의 완료 | 18세 미만 실제 가입은 보호자·법률 절차 전 유예 | 일반 도심 보도, 보행 때 사용 | 화면 미확인 조작, 안전요원, 원본 동의 이해 |
| 저시력 사용자 | 같은 연령·동의 조건, 확대·고대비 또는 음성 사용 | 색·글자만으로 핵심 정보를 구분할 수 없는 환경은 시작 차단 | 일반 도심 보도, 보행 때 사용 | 큰 글자·대비·음성 중복 방지 |
| 스마트폰 초보 사용자 | 짧은 교육·연습을 끝낼 수 있음 | 교육·핵심 정지 조작을 완료하지 못하면 현장보행 유예 | 첫 사용과 업데이트 뒤 재교육 | 쉬운 한국어·한 단계 조작·반복 연습 |

사용 빈도·현재 행동은 조사 전 사실로 단정하지 않습니다.

이 정의는 승인 정책에서 나온 제품 대상이며 실제 사용자조사 결과를 대신하지 않습니다.

<a id="dsc-03"></a>
## DSC-03 사용자 조사계획

- 적용성·상태: 사용자·사용성 연구가 출시 전 필요하므로 계획은 활성 `Draft`, 실행은 `NOT_RUN`
- 책임: 김민호가 계획·모집·분석을 관리하고, 실행 전 접근성·안전 및 보안·개인정보 검토자를 배정
- 계획 표본: 총 6명, 전맹·저시력 각 최소 3명, 스마트폰 초보 사용자 포함. 이는 모집 목표이지 실제 참여자 수가 아닙니다.
- 1단계 장소·방법: 실제 도로가 아닌 실내·폐쇄 통제공간에서 안내문 이해, 화면읽기 가입·동의, 음성명령, 오류·정지, 장착, 원본수집 설명을 관찰
- 2단계 장소·방법: 1단계와 WS-21·복구훈련·관련 안전 gate 승인 뒤 안전요원 동행 통제 보도에서 단계 시험
- 조사 질문: 위험·길안내 문장의 이해, 무버튼 조작, 오류·중지 인지, 장착 방식, 원본 동의 이해, 신고 피드백
- 분석: 과업 성공·도움 요청·오류·중단 빈도를 집계하고 전맹·저시력을 분리해 비교하며, 관찰·발언은 주제별로 코딩하고 반대 사례와 한계를 함께 기록
- 일정: 문서·시험계획 승인, 참여자 동의, 안전요원과 장소 확보 뒤. 확정 날짜와 모집 결과는 아직 없습니다.
- 보호조건: WS-21 안전계획, 참여자 동의, 즉시 중단 기준, 안전요원, 개인정보 최소 접근
- 금지: 복구 gate 전 단독 현장보행, 미성년자 보호절차 전 참여, 가리지 않은 원본의 무통제 반출

<a id="dsc-04"></a>
## DSC-04 사용자 조사 결과와 근거

정식 사용자 조사 결과는 0건입니다. 기존 문서의 가정이나 개발자 시험을 대상 사용자 결과로 바꾸지 않습니다. 실행 뒤 참여자별 외부 동의 원본과 익명화된 분석을 [registers/discovery-evidence.json](registers/discovery-evidence.json)에 연결합니다.

완료 보고서에는 비식별 참여자·방법, 동의 범위, 원자료 접근통제 ID, 과업별 관찰, 가설 지지·기각, 편향·한계, 요구·Backlog 반영 결정을 넣습니다. 현재 이 항목들은 `Planned/NOT_RUN`이며 값이 없는 것이 정상입니다.

<a id="dsc-05"></a>
## DSC-05 페르소나·사용자 프로필

적용성은 `CONDITIONAL`입니다. 현재는 조사 기반 persona가 아니라 다음 **임시 정책 프로필**만 사용합니다.

- 프로필 P-01: 화면을 보지 않고 TalkBack·음성·진동으로 조작하는 전맹 사용자. 스마트폰 숙련도와 실제 보행 습관은 미확인.
- 프로필 P-02: 큰 글자·고대비와 음성을 함께 사용하는 저시력 사용자. 시력 상태·선호 설정·실제 조작 방식은 미확인.

두 프로필은 같은 우선순위이며 실제 인물·통계·발언을 만들지 않습니다. DSC-04 근거가 충분해진 뒤에만 행동·동기·고충·빈도·대표성을 가진 persona로 승격하고 김민호가 승인합니다.

<a id="dsc-06"></a>
## DSC-06 현재·목표 사용자 여정

현재 여정은 실제 조사 전 **검증되지 않은 가설**입니다: 기존 보조수단으로 이동하면서 휴대전화 화면·지도·주변 도움을 별도로 사용하고, 가까운 위험·경로·시설 신고 정보가 한 흐름으로 연결되지 않을 수 있습니다. 이 문장을 대상 사용자 사실이나 감정으로 인용하지 않습니다. 목표 여정은 승인 정책의 11개 흐름입니다.

| 흐름 | 이름 | 쉬운 설명 |
|---|---|---|
{flow_rows}

각 흐름에서 권한 거부, 오프라인, 장애 지속, 삭제·철회와 안전정지를 정상 흐름만큼 중요하게 시험합니다.

목표 여정은 준비·교육→가입·동의·권한→기기점검→목적지·보행→위험·길안내→일시중지·복구→신고·피드백→종료·권리행사로 관리합니다. 각 단계에 사용자 목표, 필요한 입력, 실패 이유, 안전한 대체행동, 안내채널, 요구·시험 ID를 연결합니다. 현재 고충·감정·빈도는 `UNKNOWN_PENDING_RESEARCH`입니다.

<a id="dsc-07"></a>
## DSC-07 경쟁·유사 서비스 조사

상태는 `Planned/NOT_RUN`이며 정식 경쟁·유사 서비스 조사 결과는 없습니다. 조사 범주는 스마트폰 보행보조, 지도·길안내, 접근성 앱, 시설 신고 서비스입니다. 대상 사용자, 지원환경, 안전 고지, 오프라인, 접근성, 개인정보, 신고 운영, 가격, 근거 출처와 확인일을 같은 표로 비교합니다. 제품별 공식 문서와 실제 설치시험을 분리하고, 외부 제품 기능을 확인하지 않고 이름이나 성능을 만들지 않습니다. 결과가 생기면 대안·차별점·도입/제외 결정을 DSC-10·12·14에 연결합니다.

<a id="dsc-08"></a>
## DSC-08 가정·가설

| ID | 아직 확인할 가정 | 확인 방법 |
|---|---|---|
{gate_rows}

추가 가정은 Android 장착 안정성, TMAP·GPS·센서 품질, 음성 인식률, TalkBack 이해도, 관리자 1인 운영 가능성입니다. 모두 시험·조사·운영 훈련으로 확인합니다.

[registers/discovery-evidence.json](registers/discovery-evidence.json)의 가설행에는 유형, 위험, 계획 표본, 사전 통과·실패 규칙, 담당자, 완료조건과 결과를 둡니다. 결과는 현재 모두 `NOT_RUN`이며, 실패한 가설을 삭제하지 않고 Backlog·위험·변경요청으로 연결합니다.

<a id="dsc-09"></a>
## DSC-09 PoC·기술 타당성

현재 구현 후보 분류는 {implementation_text}입니다. Android TFLite·ARCore/Camera·TMAP·음성·백엔드 코드가 존재한다는 후보 사실은 기술 가능성을 탐색하는 입력입니다. 그러나 승인 정책 이후 54개 기능을 같은 build에서 재검증하지 않았으므로 제품 타당성·안전성 완료를 주장하지 않습니다.

PoC는 Android 지원기기에서 카메라→휴대전화 추론→위험판정→오프라인 음성·진동, TMAP 경로, 자동신고 대기열과 서버 receipt를 각각 재현한 뒤 하나의 후보 build로 통합하는 계획입니다. 입력 build·기기·모델·설정·데이터·실행명령·관측값·실패를 고정하고, 성공과 안전·출시 적합성을 구분합니다. 현재 결과 상태는 `NOT_REVALIDATED_AFTER_POLICY_BASELINE`입니다.

## 근거 분류

[registers/discovery-evidence.json](registers/discovery-evidence.json)은 승인 정책(A), 현재 구현 후보, legacy history를 분리합니다.

## 현재 승인 경계

DSC-03 계획은 Draft이며 DSC-04·07 실행 결과는 `Planned/NOT_RUN`, DSC-05는 임시 정책 프로필, DSC-09는 재검증 전 후보입니다. 출시는 NOT_ELIGIBLE입니다.
""" + "\n" + _artifact_management_section(DSC_COVERAGE["discovery-evidence-and-analysis.md"], DISCOVERY_PATH)


def _backlog(policy: dict[str, Any]) -> dict[str, Any]:
    area_order = {area["id"]: area["order"] for area in policy["areas"]}
    items = []
    for feature in policy["features"]:
        items.append(
            {
                "backlog_id": f"BL-{feature['id']}",
                "feature_id": feature["id"],
                "policy_clause_id": feature["policy_clause_id"],
                "title": feature["name"],
                "plain_summary": feature["plain_summary"],
                "product_scope": "MVP_BASELINED_SCOPE",
                "priority": "P0_GATE_BLOCKING" if feature["remaining_gates"] else "P0_POLICY_BASELINE_SCOPE",
                "priority_basis": "미실행 gate 종결이 필요한 기능" if feature["remaining_gates"] else "승인된 MVP 정책 범위",
                "sequence_group": area_order[feature["area_id"]],
                "related_feature_ids": feature["related_feature_ids"],
                "policy_status": "BASELINED",
                "implementation_status_before_review": feature["implementation"]["status_before_review"],
                "implementation_alignment_status": feature["implementation"]["alignment_status"],
                "remaining_gate_ids": [gate["id"] for gate in feature["remaining_gates"]],
                "requirement_id": f"RQ-{feature['id']}-001",
                "definition_of_ready": [
                    "연결 요구·인수조건과 선행 의존성이 검토됨",
                    "필요 설계·데이터·보안·시험 입력이 식별됨",
                    "차단 gate가 있으면 실행 protocol과 담당이 승인됨",
                ],
                "acceptance_scenarios": feature["verification_scenarios"],
                "definition_of_done": [
                    "승인 요구·설계와 이름 붙인 build가 추적됨",
                    "해당 인수·회귀·안전 case가 같은 build에서 통과함",
                    "미해결 결함·잔여위험·운영 제한을 공개함",
                ],
                "owner_role": "제품·내부 개발책임자",
                "assigned_person": "김민호",
                "reviewer_roles": ["QA", "접근성·안전", "보안·개인정보"],
                "relative_effort": "PENDING_REQUIREMENT_AND_DESIGN_ESTIMATION",
                "target_milestone": "MVP_NO_APPROVED_IMPLEMENTATION_DATE",
                "demo_scope": "TO_BE_SELECTED_IN_2026-07-26_DEMO_CHECKLIST",
                "status": "FORMAL_REQUIREMENT_AND_IMPLEMENTATION_REVALIDATION_PENDING",
                "completion_claimed": False,
            }
        )
    return {
        "schema_version": "walksafe.product-backlog.v1",
        "metadata": _metadata(["DSC-14"], "WalkSafe 우선순위 Backlog"),
        "prioritization_rule": "54개 승인 정책을 임의로 삭제하지 않는다. 미실행 gate가 연결된 항목을 우선 표시하고, 실제 구현 순서는 사용자 위해·선행 API/데이터/보안·시험 가능성·상대공수를 요구·설계 뒤 김민호가 승인한다.",
        "refinement_rule": "매주와 각 마일스톤 전에 준비조건·의존성·인수시나리오·상대공수·목표단계를 검토한다. 정책 범위 변경은 MGT-16 승인 없이 backlog 삭제로 처리하지 않는다.",
        "items": items,
        "summary": {
            "total": len(items),
            "completed": 0,
            "policy_baselined": 54,
            "implementation_revalidation_pending": 54,
            "effort_estimated": 0,
            "approved_target_date_assigned": 0,
        },
    }


def _stage_decisions() -> dict[str, Any]:
    common = {
        "decision_owner_role": "프로젝트·제품책임자",
        "decision_owner_person": "김민호",
        "recorded_at": AS_OF,
        "supersedes": None,
        "dissent_or_exception": "NONE_RECORDED",
    }
    return {
        "schema_version": "walksafe.stage-decision-register.v1",
        "metadata": _metadata(["DSC-15"], "WalkSafe 착수·계속·중단 판단"),
        "decisions": [
            {
                **common,
                "stage_decision_id": "STG-20260721-001",
                "stage": "FORMAL_DELIVERABLE_AUTHORING_0_TO_6",
                "decision": "START_AND_CONTINUE",
                "basis": "정책 기준선 1.0.0 승인과 135개 결정 정렬",
                "scope": "0~6 Draft 작성·구조검증·사람 검토 준비",
                "alternatives": ["작성 보류", "정책을 다시 질문", "현행 구현을 정답으로 복사"],
                "reason": "승인 답변을 구현보다 상위 기준으로 삼아 formal 산출물을 만들도록 이미 승인됨",
                "entry_condition": "정책 승인 receipt·manifest와 결정 정렬 검증 통과",
                "exit_condition": "0~6 산출물 내용·추적·상태 검사와 사람 검토 준비 완료",
                "next_review_condition": "각 bundle 내용검사 완료 또는 기준 입력 변경",
                "does_not_authorize": ["구현 완료 주장", "시험 완료 주장", "gate 면제", "출시"],
                "release_status": RELEASE_STATUS,
            },
            {
                **common,
                "stage_decision_id": "STG-20260721-002",
                "stage": "RELEASE",
                "decision": "DO_NOT_START_RELEASE_REVIEW",
                "basis": "5개 gate NOT_RUN, formal 승인·시험 증거·외부 선행조건 미완료",
                "scope": "TST-22 진입 차단",
                "alternatives": ["gate 면제", "시연을 출시증거로 사용", "근거 없이 Conditional Go"],
                "reason": "안전·개인정보·복구·비용 실행증거가 없으므로 출시 적격성을 판단할 수 없음",
                "entry_condition": "현재 상태 관측",
                "exit_condition": "5개 gate 5/5와 필요한 formal·시험·외부 증거 완료",
                "next_review_condition": "마지막 차단 gate와 TST-22 진입조건이 모두 종결됨",
                "does_not_authorize": [],
                "release_status": RELEASE_STATUS,
            },
            {
                **common,
                "stage_decision_id": "STG-20260722-003",
                "stage": "CONTROLLED_INTEGRATED_DEMO",
                "decision": "PREPARE_FOR_2026_07_26",
                "basis": "사용자가 2026-07-26 한이음 드림업 최종 통합 시연일을 확정",
                "scope": "통제환경의 대표 위험안내·길안내·신고 흐름과 제한사항 시연",
                "alternatives": ["정식 공개 배포", "모든 54개 기능 완료로 표시"],
                "reason": "확정 마일스톤을 준비하되 시연과 제품 출시·안전검증을 분리해야 함",
                "entry_condition": "시연 build·기기·데이터·체크리스트·안전 제한 식별",
                "exit_condition": "시연 실행기록·결과·알려진 제한을 남기거나 미실행 사유 기록",
                "next_review_condition": "시연 직전과 실행 직후",
                "does_not_authorize": ["공개 출시", "현장안전 합격", "5개 gate 면제"],
                "release_status": RELEASE_STATUS,
            },
        ],
        "append_only_rule": "판단이 바뀌면 기존 행을 덮어쓰지 않고 새 결정 ID·근거·영향·supersedes를 추가한다.",
    }


def _product_document(policy: dict[str, Any]) -> str:
    area_rows = "\n".join(
        f"| {area['order']} | {area['title']} | {', '.join(area['feature_ids'])} | {area['plain_scope']} |"
        for area in policy["areas"]
    )
    return _header(
        "WalkSafe 제품 정의",
        DSC_COVERAGE["product-definition.md"],
        "제품이 어떤 가치를 만들고, 무엇을 MVP로 보며, 어떤 지표와 순서로 발전시키는가?",
    ) + f"""

<a id="dsc-10"></a>
## DSC-10 제품 비전·제품 목표

**비전:** 시각장애 사용자가 일반 도심에서 화면을 계속 확인하지 않고 가까운 위험과 큰 이동 방향을 알아차리며, 손상 점자블록 신고를 돕는 접근 가능한 Android 보행 보조 서비스를 만든다.

목표는 독립 보행을 돕는 것이지만 기존 보행 보조수단을 대체하거나 안전을 보장한다고 설명하지 않습니다. 안전하지 않은 안내를 계속하는 것보다 이유를 알리고 관련 기능 또는 전체 보행 기능을 멈추는 것을 우선합니다.

| 시점·단계 | 목표 상태 | 성공 판정 |
|---|---|---|
| 2026-07-26 통합 시연 | 통제환경에서 대표 위험안내·길안내·신고 흐름과 제한사항을 한 후보 구성으로 시연 | 시연 체크리스트와 실행기록; 정식 출시 판정과 분리 |
| 제한 사용자 시험 | 실제 지원기기·안전요원·동의한 전맹/저시력 사용자가 핵심 흐름을 수행 | FP-049·050, WS-21과 사전 승인된 시험 기준 통과 |
| 정식 공개 | 기능·안전·접근성·개인정보·보안·복구 증거와 5개 gate 완료 | TST-22 Go와 TST-23 인수 기록 |

제품 목표는 과업 성공을 최우선으로 하고 안전 KPI를 균형 있게 적용합니다. 목표 단계는 앞 단계 통과만으로 자동 승격하지 않고 김민호가 근거를 검토해 승인합니다.

<a id="dsc-11"></a>
## DSC-11 KPI 기준값·목표값

| KPI | 계산식·단위 | 현재 기준값 | 목표·판정 | 책임·주기 |
|---|---|---:|---|---|
| 핵심 과업 성공률 | 도움 없이 완료한 핵심 과업/시도×100% | NOT_RUN | TST-10·15 사전 protocol의 단계별 목표 충족 | 김민호·시험 묶음별 |
| 위해 유발 결함 | S1 안전결함 수 | NOT_RUN | 출시 후보 미해결 0 | 김민호·매 build/현장시험 |
| 필수 흐름 접근 가능률 | TalkBack·음성으로 완료한 필수 흐름/대상 흐름×100% | NOT_RUN | 승인 접근성 case 전부 통과, S2 미해결 0 | 접근성 검토자·후보별 |
| 오탐·미탐 | class·환경별 FP/FN과 위해도 | NOT_RUN | class별 사전 동결 protocol 충족 | AI·안전 검토자·모델별 |
| 안내 지연 | 센서입력부터 TTS/진동까지 p50·p95·p99 ms | NOT_RUN | 지원기기 장시간 시험 전에 목표 동결 | 기술책임자·후보별 |
| GPS·이탈·STT | 정확도·stale·의도 성공률 | NOT_RUN | 환경·명령별 protocol 목표 충족 | 기술·QA·시험별 |
| 배터리·발열 | 시간당 소모율, 온도·throttle 발생 | NOT_RUN | 목표 보행시간과 안전여유 승인 뒤 목표 동결 | 기술책임자·기기별 |
| 정책 추적 | 연결된 정책·gate/68×100% | 68/68 요구 초안 연결 | RTM 고아 0 유지 | 문서통제·기준선별 |
| 결정 정렬 | 정렬 결정/135×100% | 135/135, 연결 428 | 불일치 0 유지 | MGT-15·변경 때 |
| 5개 gate | 완료 gate/5 | 0/5 | 5/5, waiver 0 | 김민호·TST-22 전 |
| 서버 저장비 | 실제 월 저장·요청·복원 비용(KRW) | 계산 가정 17,550원, 실측 NOT_RUN | 월 30,000원 이하 | 운영책임자·매월 |
| 출시 준비도 | TST-22 판정 | NOT_ELIGIBLE | 사람 승인 Go 또는 Conditional Go | 김민호·릴리스별 |

측정값이 없는 KPI는 `0`으로 꾸미지 않고 `NOT_RUN`으로 둡니다. 성능·안전 목표는 측정 결과를 보고 유리하게 바꾸지 않도록 시험 전에 protocol·지원기기·환경·계산식·목표·허용오차를 동결합니다.

<a id="dsc-12"></a>
## DSC-12 MVP 범위

MVP는 “모든 아이디어”가 아니라 승인된 54개 기능 정책의 안전한 첫 제품 범위입니다. 각 기능을 한 번에 완성했다고 보지 않고 요구·설계·구현·시험 단계로 나눕니다.

| 순서 | 기능 영역 | 정책 ID | 쉬운 범위 |
|---:|---|---|---|
{area_rows}

### MVP 밖 또는 별도 승인 대상

- 보호자 추적·넘어짐 탐지
- Web/PWA를 정식 사용자·관리자 제품으로 출시
- 기존 보행 보조수단 대체·안전 보장 주장
- 미검증 모델 자동교체·무통제 원본 외부 제공
- gate와 인수 없이 production 출시

### MVP 인수 시나리오

| 시나리오 | 포함 기능 | 완료 기준 |
|---|---|---|
| 보행 준비·위험 안내 | 가입·동의·권한·기기점검·카메라·거리·위험·TTS·진동 | 지원기기에서 정상·제한·정지 흐름을 실행하고 안전 차단결함 0 |
| 목적지·길안내 | 음성 목적지·TMAP 경로·남은거리·도착·이탈 확인 | GPS 불신·이탈·TMAP 장애에서 오래된 방향을 중지하고 사용자 판단 요청 |
| 손상 점자블록 신고 | 후보·중복·대기열·서버 receipt·관리자 검수·사용자 권리 | 보행 중 미전송, 승인망·정지 조건, 중복방지·보존·삭제·감사 추적 통과 |
| 운영·복구 | 관리자 인증·모니터링·백업·용량·서비스 종료 | 복구훈련·비용·용량·보안·개인정보 gate와 runbook 통과 |

MVP 범위를 늘리거나 빼려면 MGT-16 영향분석으로 사용자·안전·데이터·일정·시험 영향을 기록하고 김민호가 승인합니다. 기존 보조수단 대체 표현과 제외 기능은 별도 정책 기준선 없이 추가할 수 없습니다.

<a id="dsc-13"></a>
## DSC-13 제품 로드맵

| 단계 | 사용자·프로젝트 가치 | 진입 조건 | 종료 조건 | 검토 시점 |
|---|---|---|---|---|
| 1. 정책·결정 정렬 | 무엇을 만들지 한 기준으로 통일 | 사용자 답변 완료 | 63개 정책 확인·135개 결정 정렬 | 완료 2026-07-21 |
| 2. formal 산출물 완성 | 요구·설계·시험이 같은 기준을 사용 | 정책 기준선 | 0~6 내용·추적·상태검사와 사람 검토 | 진행 중; 7월 26일 시연 전 범위 검토 |
| 3. 통제 시연 | 대표 흐름과 제한을 한 구성으로 확인 | 시연 대상 build·체크리스트 | 시연기록·알려진 제한 공개 | 2026-07-26 |
| 4. 구현 Gap 정렬 | 현재 후보를 승인 설계에 맞춤 | 관리·요구·설계 검토 | 이름 붙인 build·manifest·미해결 Gap | 문서 기준선 뒤 |
| 5. 기술·실기기 시험 | 기능·성능·복구 근거 생성 | 시험계획·환경·데이터 승인 | 단위→계약→통합→실기기 결과와 결함 | build별 |
| 6. 대상 사용자·독립 검토 | 실제 접근성·현장안전·개인정보 근거 | WS-21·복구훈련·안전 gate | 승인된 사용자시험·독립검토 결과 | 참여자·검토자 확보 뒤 |
| 7. 단계 배포·인수 | 제한된 사용자부터 안전하게 확대 | 5개 gate와 TST-22 진입조건 완료 | Go/Conditional Go와 TST-23 인수 | 릴리스별 |

단계별 선행관계와 종료조건이 우선이며, 근거 없는 공개 출시 날짜는 만들지 않습니다. 사람·기기·외부 검토·cloud가 배정되면 MGT-07과 함께 날짜·buffer를 김민호가 승인합니다.

## 남은 gate

| ID | 내용 | 상태 | 완료조건 |
|---|---|---|---|
{_gate_rows(policy)}

## 현재 승인 경계

제품 정책은 승인됐지만 KPI·MVP·로드맵 문서 자체는 Draft입니다. 구현·시험·출시는 완료되지 않았습니다.
""" + "\n" + _artifact_management_section(DSC_COVERAGE["product-definition.md"], PRODUCT_PATH)


def _product_registers_document() -> str:
    return _header(
        "WalkSafe 제품 원장",
        DSC_COVERAGE["product-registers.md"],
        "54개 기능의 우선순위와 프로젝트를 계속할지·중단할지를 어디에서 지속 관리하는가?",
    ) + """

<a id="dsc-14"></a>
## DSC-14 우선순위 Backlog

[registers/backlog.json](registers/backlog.json)에 54개 기능 정책을 모두 등록했습니다. 현재 54개 모두 정책은 기준선화됐지만 formal requirement·설계·구현 재검증·시험이 남았습니다. 정책 목록 순서를 임의의 구현 완료율로 바꾸지 않습니다.

54개는 모두 MVP 기준 범위입니다. 미실행 gate가 직접 연결된 항목은 `P0_GATE_BLOCKING`, 나머지는 `P0_POLICY_BASELINE_SCOPE`로 표시합니다. 각 행에는 관련 기능, 준비조건, 정책의 검증 시나리오, 완료조건, 담당자 김민호, 필요한 검토 역할, 상대공수·목표일의 미확정 상태를 기록했습니다. 실제 개발 순서와 공수는 요구·설계 의존성을 확인한 뒤 승인하며, 7월 26일 시연 대상은 별도 체크리스트에서 대표 흐름만 고릅니다.

<a id="dsc-15"></a>
## DSC-15 착수·계속·중단 판단

[registers/stage-decisions.json](registers/stage-decisions.json)의 현재 판단은 다음과 같습니다.

- 0~6 정식 Draft 작성: `START_AND_CONTINUE`
- 구현·시험 완료 주장: 허용하지 않음
- 5개 gate 면제: 허용하지 않음
- 출시 준비도 심사: `DO_NOT_START_RELEASE_REVIEW`
- 출시 상태: `NOT_ELIGIBLE`
- 2026-07-26 통제 통합 시연: 준비하되 공개 출시·현장안전 합격으로 사용하지 않음

각 판단에는 결정권자 김민호, 근거, 검토한 대안, 진입·종료조건, 다음 재검토 조건과 허용하지 않는 행동을 남깁니다. 판단이 바뀌면 과거 행을 덮어쓰지 않고 새 ID로 대체 관계를 기록합니다.

## 갱신 조건

- 정책·범위 변경
- formal 문서 검토·승인·반려
- 구현정렬 결과와 새 결함·위험
- gate 또는 외부 선행조건 결과
- 자원·일정·법률·외부 API 변경

이 원장은 상태가 바뀔 때 새 결정 ID와 근거를 추가하며 과거 판단을 덮어쓰지 않습니다.
""" + "\n" + _artifact_management_section(DSC_COVERAGE["product-registers.md"], PRODUCT_REGISTERS_PATH)


def _source_bindings() -> dict[str, dict[str, str]]:
    return {
        "policy": _binding(POLICY_PATH),
        "policy_approval_record": _binding(POLICY_APPROVAL_PATH),
        "policy_baseline_manifest": _binding(POLICY_MANIFEST_PATH),
        "aligned_decision_register": _binding(ALIGNED_DECISIONS_PATH),
        "artifact_catalog": _binding(CATALOG_PATH),
        "project_decision_answers": _binding(PROJECT_DECISION_ANSWERS_PATH),
        "fp035_correction_candidate_not_effective": _binding(FP035_CORRECTION_PATH),
        "policy_approval_validator_source": _binding(POLICY_APPROVAL_VALIDATOR_PATH),
        "decision_alignment_validator_source": _binding(DECISION_ALIGNMENT_VALIDATOR_PATH),
        "generator": _binding(GENERATOR_PATH),
    }


def _build_outputs() -> dict[Path, bytes]:
    policy, _manifest, decisions, _catalog = _validate_inputs()
    outputs: dict[Path, bytes] = {
        CHARTER_PATH: _md_bytes(_charter(policy)),
        PMP_PATH: _md_bytes(_pmp(policy)),
        CONTROL_REGISTER_PATH: _md_bytes(_control_document(policy, decisions)),
        WBS_PATH: _json_bytes(_wbs()),
        SCHEDULE_PATH: _json_bytes(_schedule()),
        STAKEHOLDERS_PATH: _json_bytes(_stakeholders()),
        RACI_PATH: _json_bytes(_raci()),
        RAID_PATH: _json_bytes(_raid(policy)),
        DECISIONS_PATH: _json_bytes(_decision_pointer(decisions)),
        CHANGES_PATH: _json_bytes(_change_requests()),
        ACTIONS_PATH: _json_bytes(_actions()),
        DASHBOARD_PATH: _json_bytes(_dashboard()),
        DISCOVERY_PATH: _md_bytes(_discovery_document(policy)),
        PRODUCT_PATH: _md_bytes(_product_document(policy)),
        PRODUCT_REGISTERS_PATH: _md_bytes(_product_registers_document()),
        DISCOVERY_EVIDENCE_PATH: _json_bytes(_discovery_evidence(policy)),
        BACKLOG_PATH: _json_bytes(_backlog(policy)),
        STAGE_DECISIONS_PATH: _json_bytes(_stage_decisions()),
    }
    generated_files = []
    for path, content in sorted(outputs.items(), key=lambda item: _rel(item[0])):
        if path.is_relative_to(MGT_DIR):
            codes = MGT_COVERAGE.get(path.relative_to(MGT_DIR).as_posix(), [])
        else:
            codes = DSC_COVERAGE.get(path.relative_to(DSC_DIR).as_posix(), [])
        if not codes:
            codes = sorted(
                code
                for code, support_paths in ARTIFACT_SUPPORT_PATHS.items()
                if path in support_paths
            )
        generated_files.append(
            {"path": _rel(path), "sha256": _sha_bytes(content), "byte_length": len(content), "artifact_type_ids": codes}
        )
    source_bindings = _source_bindings()
    manifest = {
        "schema_version": "walksafe.formal-management-discovery-draft-manifest.v1",
        "metadata": {
            **_metadata(
                [f"MGT-{number:02d}" for number in range(1, 19)]
                + [f"DSC-{number:02d}" for number in range(1, 16)],
                "WalkSafe MGT·DSC 정식 산출물 Draft manifest",
            ),
            "manifest_id": "WS-FORMAL-MGT-DSC-DRAFT-20260721-001",
            "controlled_revision": 1,
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha(source_bindings),
        "coverage": {
            "MGT": {"expected": 18, "covered": 18},
            "DSC": {"expected": 15, "covered": 15},
            "missing_artifact_type_ids": [],
            "duplicate_artifact_type_ids": [],
            "artifact_management_rule_sections": 33,
        },
        "approved_planning_inputs_reflected": {
            "project_owner": "김민호",
            "delivery_mode": "1인 프로젝트; 필요한 독립·외부 검토는 미배정으로 유지",
            "controlled_demo_date": "2026-07-26",
            "separately_allocated_project_budget": "NONE",
            "weekday_support_hours": "09:00-18:00 Asia/Seoul",
            "release_strategy": "단계 배포; TST-22와 gate 전 공개 출시 금지",
            "research_execution_status": "NOT_RUN",
            "source_answer_refs": {
                "budget_and_demo": "notes.Q-GOV-011",
                "milestone_management": "answers.Q-GOV-011",
                "support_hours_mode": "answers.Q-OPS-002",
            },
        },
        "generated_files": generated_files,
        "remaining_gates": [
            {"id": gate["id"], "status": "NOT_RUN", "waived": False}
            for gate in policy["remaining_gates"]
        ],
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "decision_alignment_status": "COMPLETE",
            "formal_deliverable_lifecycle_status": "DRAFT",
            "formal_deliverables_approved": False,
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
    }
    manifest["manifest_content_sha256"] = _object_sha(manifest)
    outputs[MANIFEST_PATH] = _json_bytes(manifest)
    return outputs


def _validate_outputs(outputs: dict[Path, bytes]) -> None:
    mgt_codes = [code for values in MGT_COVERAGE.values() for code in values]
    dsc_codes = [code for values in DSC_COVERAGE.values() for code in values]
    _require(len(mgt_codes) == len(set(mgt_codes)) == 18, "MGT output coverage invalid")
    _require(len(dsc_codes) == len(set(dsc_codes)) == 15, "DSC output coverage invalid")
    for relative, codes in MGT_COVERAGE.items():
        text = outputs[MGT_DIR / relative].decode("utf-8")
        for code in codes:
            _require(f'id="{code.lower()}"' in text, f"MGT anchor missing: {code}")
            _require(f"### {code} " in text, f"MGT management rule missing: {code}")
    for relative, codes in DSC_COVERAGE.items():
        text = outputs[DSC_DIR / relative].decode("utf-8")
        for code in codes:
            _require(f'id="{code.lower()}"' in text, f"DSC anchor missing: {code}")
            _require(f"### {code} " in text, f"DSC management rule missing: {code}")
    management_labels = [
        "작성 목적", "필수·조건부와 적용 조건", "들어갈 내용", "입력자료", "선후관계",
        "역할", "형식·보관 위치", "완료·승인 기준", "갱신 조건·주기", "변경·폐기·대체",
    ]
    bundled_coverage = {
        CHARTER_PATH: MGT_COVERAGE[CHARTER_PATH.name],
        PMP_PATH: MGT_COVERAGE[PMP_PATH.name],
        CONTROL_REGISTER_PATH: MGT_COVERAGE[CONTROL_REGISTER_PATH.name],
        DISCOVERY_PATH: DSC_COVERAGE[DISCOVERY_PATH.name],
        PRODUCT_PATH: DSC_COVERAGE[PRODUCT_PATH.name],
        PRODUCT_REGISTERS_PATH: DSC_COVERAGE[PRODUCT_REGISTERS_PATH.name],
    }
    for path, codes in bundled_coverage.items():
        text = outputs[path].decode("utf-8")
        for label in management_labels:
            _require(
                text.count(f"**{label}:**") == len(codes),
                f"management dimension coverage invalid: {_rel(path)}: {label}",
            )
    backlog = json.loads(outputs[BACKLOG_PATH])
    _require(len(backlog["items"]) == 54 and backlog["summary"]["completed"] == 0, "backlog boundary invalid")
    _require(
        backlog["summary"]["effort_estimated"] == 0
        and all(item["assigned_person"] == "김민호" for item in backlog["items"]),
        "backlog planning boundary invalid",
    )
    evidence = json.loads(outputs[DISCOVERY_EVIDENCE_PATH])
    _require(
        evidence["research_status"]["participant_count"] == 0
        and evidence["research_plan"]["execution_status"] == "NOT_RUN",
        "research evidence is overstated",
    )
    raid = json.loads(outputs[RAID_PATH])
    _require(raid["summary"]["gate_count"] == 5 and raid["summary"]["waived_gate_count"] == 0, "RAID gate boundary invalid")
    fp035 = next(row for row in raid["rows"] if row["raid_id"] == "RAID-011")
    _require(
        fp035["status"] == "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
        "FP-035 directive status invalid",
    )
    _require(
        "보행 중 전송하지 않는다" in fp035["captured_owner_directive"]
        and "Wi-Fi에서만" in fp035["captured_owner_directive"],
        "FP-035 directive text invalid",
    )
    dashboard = json.loads(outputs[DASHBOARD_PATH])
    _require(dashboard["status"]["formal_approvals"] == 0 and dashboard["status"]["release_status"] == RELEASE_STATUS, "dashboard boundary invalid")
    schedule = json.loads(outputs[SCHEDULE_PATH])
    demo = next(row for row in schedule["milestones"] if row["milestone_id"] == "MS-DEMO")
    _require(
        demo["target_date"] == "2026-07-26" and demo["actual_date"] is None,
        "controlled demo boundary invalid",
    )
    pmp = outputs[PMP_PATH].decode("utf-8")
    for known_input in (
        "김민호",
        "2026년 7월 26일",
        "별도로 배정·승인된 프로젝트 총예산: **없음**",
        "평일 09:00~18:00",
    ):
        _require(known_input in pmp, f"approved planning input missing from PMP: {known_input}")
    manifest = json.loads(outputs[MANIFEST_PATH])
    _require(manifest["coverage"]["MGT"]["covered"] == 18 and manifest["coverage"]["DSC"]["covered"] == 15, "manifest coverage invalid")
    _require(
        manifest["source_bindings"].get("policy_approval_record")
        == _binding(POLICY_APPROVAL_PATH),
        "manifest approval-record source binding differs",
    )
    _require(
        manifest["source_bindings"].get("project_decision_answers")
        == _binding(PROJECT_DECISION_ANSWERS_PATH),
        "manifest project-decision source binding differs",
    )
    _require(
        manifest["source_bindings"].get("fp035_correction_candidate_not_effective")
        == _binding(FP035_CORRECTION_PATH),
        "manifest FP-035 correction binding differs",
    )
    generated_by_path = {
        item["path"]: item["artifact_type_ids"]
        for item in manifest["generated_files"]
    }
    for code, support_paths in ARTIFACT_SUPPORT_PATHS.items():
        for support_path in support_paths:
            _require(
                code in generated_by_path.get(_rel(support_path), []),
                f"supporting register ownership is missing: {code}/{_rel(support_path)}",
            )
    for binding_name, source_path in (
        ("policy_approval_validator_source", POLICY_APPROVAL_VALIDATOR_PATH),
        ("decision_alignment_validator_source", DECISION_ALIGNMENT_VALIDATOR_PATH),
    ):
        _require(
            manifest["source_bindings"].get(binding_name) == _binding(source_path),
            f"manifest validator source binding differs: {binding_name}",
        )
    _require(all(gate["status"] == "NOT_RUN" and not gate["waived"] for gate in manifest["remaining_gates"]), "manifest gate boundary invalid")


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
    except (ManagementDiscoveryError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"{'verified' if args.check else 'generated'} {len(outputs)} MGT/DSC formal Draft files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
