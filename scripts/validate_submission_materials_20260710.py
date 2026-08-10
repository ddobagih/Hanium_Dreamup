#!/usr/bin/env python3
"""Validate the evidence pack before generating the official Office forms."""

from __future__ import annotations

import sys

if __name__ == "__main__":
    _verified_submission_commit = getattr(
        sys.modules.get("__main__"), "_walksafe_submission_source_commit", None
    )
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, "safe_path", False)),
        sys.flags.dont_write_bytecode,
    )
    if (
        any(value != 1 for value in _startup_flags)
        or "site" in sys.modules
        or not isinstance(_verified_submission_commit, str)
        or len(_verified_submission_commit) != 40
        or any(character not in "0123456789abcdef" for character in _verified_submission_commit)
    ):
        raise SystemExit(
            "WalkSafe submission CLI requires Python -I -S -B through the submission runner"
        )

import hashlib
import json
import re
from datetime import datetime
from pathlib import Path

from PIL import Image

from submission_manifest_policy import (
    ALL_SUBMISSION_GENERATED_PATHS,
    ASSET_ARTIFACT_PATHS,
    ASSET_DIRECTORY_FILES,
    ASSET_REPOSITORY_INPUT_PATHS,
    ASSET_SYSTEM_INPUT_PATHS,
    build_tool_provenance,
    record_bundle_sha256,
    require_exact_directory_entries,
    require_exact_file_set,
    require_exact_manifest_paths,
    source_revision,
    submission_toolchain_attestation,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
MATERIALS = REPO_ROOT / "docs/submission/form_materials"
ASSETS = MATERIALS / "assets"
EVIDENCE_RESULTS_PNG_SEMANTIC_KEY = "walksafe.evidence.rendered-fields.v1"

EXPECTED_TEMPLATE_HASHES = {
    "templates/[서식1] 2026 한이음 드림업 개발보고서 양식.docx": "b8d52b810760c54c61646566ea3ee78208f43d7b1c0e3b65d53d99dc8cde22e3",
    "templates/[서식2] 2026년_제작설계서_일반(개인정보 기재x).pptx": "140628cdd6b160c8d86a2919ce01620c274c54362bd71c9e82a77670a7621336",
}
EXPECTED_ASSETS = {
    "web_main_mobile.png": (780, 1688),
    "web_main_desktop.png": (1440, 900),
    "admin_desktop.png": (1440, 1000),
    "system_architecture.png": (1600, 900),
    "risk_processing_flow.png": (1600, 900),
    "report_csv_flow.png": (1600, 900),
    "reports_erd.png": (1600, 900),
    "value_flow.png": (1600, 900),
    "model_quality_gate.png": (1600, 900),
    "problem_solution_map.png": (1600, 900),
    "use_case_swimlane.png": (1600, 900),
    "ui_state_map.png": (1600, 900),
    "ui_screen_storyboard.png": (1600, 1500),
    "navigation_state_flow.png": (1600, 900),
    "risk_timeline.png": (1600, 900),
    "evidence_results.png": (1600, 900),
    "scope_change_map.png": (1600, 900),
    "adoption_roadmap.png": (1600, 900),
}
REQUIRED_MATERIALS = [
    "README.md",
    "00_작성기준_및_가정.md",
    "01_주장_근거_검증_매트릭스.md",
    "02_기능_진척도_산정표.md",
    "03_수행일정_계획대비실적.md",
    "04_개발보고서_본문원고.md",
    "05_제작설계서_34장_원고.md",
    "06_외부근거_출처표.md",
    "07_화면_시각자료_목록.md",
    "08_도식_정의_및_검증.md",
    "09_제출_사실_기준.json",
    "assets/README.md",
]

EXPECTED_DATABASE_TABLE_ROLES = (
    ("reports", "data"),
    ("report_status_audits", "audit"),
    ("report_export_audits", "audit"),
    ("report_read_audits", "audit"),
    ("actor_rate_limit_events", "rate_event"),
)

NON_METRIC_CAPABILITY_MARKERS = (
    "CAMERA_NON_METRIC_ADVISORY",
    "CAMERA_IMU_NON_METRIC",
    "TMAP_ONLY",
    "low 좌/중앙/우",
    "3 distinct frame+700ms",
    "fresh IMU",
    "reports=false",
    "기존 Web alertable risk",
    "명시 동의 damaged-report",
    "3A bounded sequential handoff",
    "UNVERIFIED_OPEN",
)

SOURCE_SEMANTIC_MARKERS = {
    "docs/submission/form_materials/04_개발보고서_본문원고.md": (
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "startup reconciliation",
        "sslmode=verify-full",
        "gssencmode=disable",
        "VOICE_SERVICE_TOKEN",
        "body copy 전 auth·Content-Length·global/actor/IP rate",
        "copy 중 byte 상한",
        "copy 후 duration 검증·inference queue",
        "OpenAPI",
        "TMAP-only",
        "선택적 IMU",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "docs/submission/form_materials/05_제작설계서_34장_원고.md": (
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "startup reconciliation",
        "sslmode=verify-full",
        "gssencmode=disable",
        "body copy 전 auth·Content-Length·global/actor/IP rate",
        "copy 중 byte 상한",
        "copy 후 duration·inference queue",
        "worker=1",
        "OpenAPI",
        "TMAP-only",
        "선택적 IMU",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "docs/submission/deliverables/요구사항_정의서.md": (
        "선택적 IMU",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "docs/submission/deliverables/유스케이스_정의서.md": (
        "IMU는 제공될 때",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "docs/submission/drafts/요구사항_추적표.md": (
        "IMU 선택",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "docs/submission/deliverables/엔티티관계도_테이블정의서.md": (
        "report_read_audits",
        "actor_rate_limit_events",
        "5개 application table",
        "durable journal",
        "startup reconciliation",
    ),
    "docs/submission/deliverables/서비스_구성도_및_흐름도.md": (
        "report_read_audits",
        "actor_rate_limit_events",
        "sslmode=verify-full",
        "gssencmode=disable",
        "VOICE_SERVICE_TOKEN",
        "body copy 전 auth·Content-Length·global/actor/IP rate",
        "copy 중 byte 상한",
        "copy 후 duration·inference queue",
        "OpenAPI",
        "TMAP-only",
    ),
    "docs/submission/deliverables/기능처리도_알고리즘명세서.md": (
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "startup reconciliation",
        "Web frame·Web report·Android report·field telemetry",
        "sslmode=verify-full",
        "gssencmode=disable",
        "VOICE_SERVICE_TOKEN",
        "body copy 전 auth·Content-Length·global/actor/IP rate",
        "copy 중 chunk byte 상한",
        "copy 후 audio duration",
        "inference queue",
        "OpenAPI",
        "TMAP-only",
    ),
    "docs/submission/deliverables/화면설계서_UIUX_정의서.md": (
        "Web frame",
        "Web report",
        "Android report",
        "field telemetry",
        "in-flight",
        "GPS/Location",
    ),
    "docs/submission/deliverables/프로그램목록_핵심소스코드_개발환경.md": (
        "202607160001",
        "report_read_audits",
        "actor_rate_limit_events",
        "sslmode=verify-full",
        "gssencmode=disable",
        "VOICE_SERVICE_TOKEN",
        "body copy 전 auth·Content-Length·global/actor/IP rate",
        "copy 중 byte 상한",
        "copy 후 duration·inference queue",
        "WALKSAFE_WEB_REPLICAS=1",
    ),
    "scripts/build_submission_assets_20260710.py": (
        "5개 application table",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "sslmode=verify-full",
        "gssencmode=disable",
        "pre-copy auth/Content-Length",
        "global/actor/IP rate",
        "post-copy duration/inference queue",
        "Web frame/report",
        "TMAP-only",
        "IMU 선택",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "scripts/build_submission_forms_20260710.py": (
        "5개 application table",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "sslmode=verify-full",
        "gssencmode=disable",
        "body copy 전 auth·Content-Length·global/actor/IP rate",
        "copy 중 byte 상한",
        "copy 후 duration·inference queue",
        "Web frame·Web report·Android report·telemetry",
        "OpenAPI",
        "TMAP-only",
        "IMU 선택",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "scripts/build_design_documents_20260710.py": (
        "5개 application table",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "Web frame/report",
        "useRiskFeedback.ts",
        "nonmetric-hazard-advisory.ts",
        "AndroidLocalTactileCapability.kt",
        "AndroidNonMetricObstacleAdvisoryPolicy.kt",
    ),
    "scripts/promote_submission_final_20260713.py": (
        "IMU 선택",
    ) + NON_METRIC_CAPABILITY_MARKERS,
    "scripts/build_midterm_design_ppt_20260712.py": (
        "5개 application table",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "sslmode=verify-full",
        "gssencmode=disable",
        "pre-copy auth/Content-Length/global·actor·IP rate",
        "post-copy duration/inference queue",
        "Web frame/report·Android report",
        "OpenAPI",
    ),
    "scripts/build_midterm_design_ppt_template_faithful_20260712.py": (
        "5개 application table",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "sslmode=verify-full",
        "gssencmode=disable",
        "pre auth/length/rate",
        "post-copy inference queue",
        "disconnect",
        "Web frame/report·Android report·telemetry",
        "OpenAPI",
    ),
    "scripts/build_midterm_design_ppt_practical_deliverables_20260712.py": (
        "5개 application table",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "Web frame·Web report·Android report·telemetry",
        "in-flight",
    ),
}

SOURCE_SEMANTIC_FORBIDDEN_PATTERNS = (
    re.compile(r"PostGIS\s+3\s+tables", re.IGNORECASE),
    re.compile(r"현재\s+영속\s+테이블은\s+위\s+3개"),
    re.compile(r"격리\s+DB·3\s+tables", re.IGNORECASE),
    re.compile(r"Alembic(?:\s+head)?\s+`?202607130002`?", re.IGNORECASE),
    re.compile(r"pre-copy\s+(?:size|byte)[/·]rate[/·]duration[/·]queue", re.IGNORECASE),
    re.compile(r"(?:body\s+)?copy\s+전\s+byte[/·]rate[/·]duration[/·]queue", re.IGNORECASE),
    re.compile(r"복사하기\s+전[^.\n]{0,100}duration[/·]queue", re.IGNORECASE),
    re.compile(r"(?:pre-copy\s+(?:자원\s+)?제한|pre-copy\s+limits?|body\s+copy\s+전\s+제한)", re.IGNORECASE),
    re.compile(
        r"(?:Web(?:/|·|과)Android|Web과\s+Android)[^.\n]{0,160}"
        r"(?:모두|현재)[^.\n]{0,120}(?:projection[^.\n]{0,40}(?:없|부재|UNAVAILABLE)|TMAP-only[^.\n]{0,60}(?:steering[^.\n]{0,30}비활성|항상))",
        re.IGNORECASE,
    ),
    re.compile(r"Android도\s+projection\s+부재[^.\n]{0,60}UNAVAILABLE", re.IGNORECASE),
)

SOURCE_SEMANTIC_SCAN_GLOBS = (
    "docs/submission/form_materials/*.md",
    "docs/submission/form_materials/*.json",
    "docs/submission/form_materials/assets/README.md",
    "docs/submission/deliverables/*.md",
    "docs/submission/drafts/*.md",
    "docs/submission/design_documents/README.md",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def _mapping(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def verification_snapshot_boundary(
    status: str,
    executed_at: str,
    errors: list[str],
) -> str | None:
    """Independently classify the canonical verification snapshot state."""

    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", executed_at) is None:
        errors.append("verification snapshot executed_at must be an ISO date")
        return None
    try:
        datetime.strptime(executed_at, "%Y-%m-%d")
    except ValueError:
        errors.append("verification snapshot executed_at must be an ISO date")
        return None
    if status == f"HISTORICAL_PRE_SOURCE_FREEZE_{executed_at}":
        return "과거 pre-source-freeze snapshot · 현재 hardening 수치 아님"
    if status == f"SOURCE_FREEZE_CANDIDATE_{executed_at}":
        return "source-freeze 후보 검증 snapshot · 최종 승인 source-freeze 아님"
    if status == f"SOURCE_FREEZE_APPROVED_{executed_at}":
        return "승인된 source-freeze 검증 snapshot"
    errors.append(f"unsupported verification snapshot status: {status}")
    return None


CURRENT_ANDROID_DEVICE_VERIFICATION = {
    "status": "NOT_RUN_CURRENT_SOURCE_FREEZE",
    "executed_at": None,
    "passed": None,
    "total": None,
}
HISTORICAL_ANDROID_DEVICE_EVIDENCE = {
    "status": "PASS_HISTORICAL_EVIDENCE",
    "executed_at": "2026-07-13",
    "device": "SM-G981N",
    "scope": "unified/legacy TFLite asset contract and load/invoke instrumentation",
    "passed": 2,
    "total": 2,
}
APPROVED_SOURCE_FREEZE_POLICY = (
    "사용자가 승인한 source-freeze commit의 자동검증 snapshot이며 최종 source-freeze "
    "근거로 채택했다. Field 또는 Release 증거로 확대하지 않고 PASS와 FAIL을 함께 "
    "기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze Android "
    "device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 SM-G981N "
    "2/2는 별도 historical evidence로만 보존한다."
)


def historical_source_freeze_policy(executed_at: str) -> str:
    return (
        f"이 snapshot은 {executed_at} 기준의 과거 계층 증거이며 현재 hardening 수치가 "
        "아니다. PASS와 FAIL을 함께 기록하고 Unit·격리 DB·build를 Field 또는 Release "
        "증거로 확대하지 않으며, source freeze 후 전체 재실행 수치로 교체한다. current "
        "source-freeze에서 Android device run이 없으면 NOT_RUN_CURRENT_SOURCE_FREEZE로 "
        "기록하고, 2026-07-13 SM-G981N 2/2는 별도 historical evidence로만 보존한다."
    )


def candidate_source_freeze_policy(executed_at: str) -> str:
    return (
        f"이 snapshot은 {executed_at} hardening 작업 후보의 임시 자동검증 수치이며, 새 "
        "clean source candidate의 전체 재현 대조를 통과해야 채택한다. 사용자가 승인한 "
        "최종 source-freeze commit이나 Field 또는 Release 증거가 아니고 PASS와 FAIL을 "
        "함께 기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze "
        "Android device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 "
        "SM-G981N 2/2는 별도 historical evidence로만 보존한다."
    )


def validate_android_device_verification(
    snapshot: dict[str, object],
    errors: list[str],
) -> tuple[dict[str, object], dict[str, object], str, str] | None:
    verification = snapshot.get("android_device_verification")
    if not isinstance(verification, dict) or set(verification) != {
        "current_source_freeze",
        "historical_evidence",
    }:
        errors.extend(
            (
                "canonical current source-freeze Android device verification contract drifted",
                "canonical historical Android device evidence contract drifted",
            )
        )
        return None
    current = verification.get("current_source_freeze")
    historical = verification.get("historical_evidence")
    current_shape = isinstance(current, dict) and set(current) == {
        "status",
        "executed_at",
        "passed",
        "total",
    }
    historical_shape = isinstance(historical, dict) and set(historical) == {
        "status",
        "executed_at",
        "device",
        "scope",
        "passed",
        "total",
    }
    snapshot_date = snapshot.get("executed_at")
    current_valid = bool(
        current_shape and current == CURRENT_ANDROID_DEVICE_VERIFICATION
    )
    current_text = "현재 source-freeze device: NOT_RUN_CURRENT_SOURCE_FREEZE"
    require(
        current_valid,
        "canonical current source-freeze Android device verification contract drifted",
        errors,
    )

    historical_valid = False
    historical_text = ""
    if historical_shape:
        assert isinstance(historical, dict)
        try:
            parsed_snapshot_date = datetime.strptime(str(snapshot_date), "%Y-%m-%d")
        except ValueError:
            pass
        else:
            historical_valid = bool(
                historical == HISTORICAL_ANDROID_DEVICE_EVIDENCE
                and type(historical.get("passed")) is int
                and type(historical.get("total")) is int
                and datetime(2026, 7, 13) <= parsed_snapshot_date
            )
        historical_text = (
            f"과거 evidence {historical.get('executed_at')} {historical.get('device')}: "
            f"instrumentation {historical.get('passed')}/{historical.get('total')} "
            "PASS_HISTORICAL_EVIDENCE"
        )
    require(
        historical_valid,
        "canonical historical Android device evidence contract drifted",
        errors,
    )
    if not current_valid or not historical_valid:
        return None
    assert isinstance(current, dict) and isinstance(historical, dict)
    return current, historical, current_text, historical_text


def validate_core_semantic_facts(facts: object, errors: list[str]) -> None:
    """Validate security/privacy/storage facts independently of rendered artifacts."""
    root = _mapping(facts)
    require(
        root.get("schema_version") == "walksafe.submission_facts.v2",
        "unexpected canonical facts schema for core semantics",
        errors,
    )
    product_decisions = _mapping(root.get("recorded_product_decisions"))
    require(
        set(product_decisions) == {
            "recorded_at",
            "web",
            "android_arcore_unsupported",
            "equal_severity",
            "source_freeze",
        }
        and product_decisions.get("recorded_at") == "2026-07-16"
        and product_decisions.get("web")
        == "CAMERA_NON_METRIC_ADVISORY_WITH_TMAP_ROUTE_AUTHORITY"
        and product_decisions.get("android_arcore_unsupported")
        == "INSTALLABLE_CAMERA_IMU_NON_METRIC_LIMITED_MODE"
        and _mapping(product_decisions.get("equal_severity")) == {
            "option": "3A",
            "policy": "BOUNDED_SEQUENTIAL_HANDOFF_WITH_DELIVERY_RECHECK",
        }
        and _mapping(product_decisions.get("source_freeze")) == {
            "option": "4A",
            "status": "LOCAL_COMMIT_AUTHORIZED_NO_PUSH",
            "push_authorized": False,
        },
        "canonical recorded product decisions drifted",
        errors,
    )
    snapshot = _mapping(root.get("verification_snapshot"))
    as_of_date = _text(root.get("as_of_date"))
    snapshot_date = _text(snapshot.get("executed_at"))
    snapshot_status = _text(snapshot.get("status"))
    counts = snapshot.get("counts")
    expected_count_keys = {
        "unit_python",
        "functional_python",
        "integration_python",
        "backend_full",
        "voice",
        "android_jvm",
        "web_trace_count",
        "web_trace_files",
    }
    require(
        isinstance(counts, dict)
        and set(counts) == expected_count_keys
        and all(
            not isinstance(value, bool) and isinstance(value, int) and value >= 1
            for value in counts.values()
        ),
        "canonical verification counts must exclude device evidence and remain positive",
        errors,
    )
    dates_valid = False
    try:
        if (
            re.fullmatch(r"\d{4}-\d{2}-\d{2}", as_of_date) is None
            or re.fullmatch(r"\d{4}-\d{2}-\d{2}", snapshot_date) is None
        ):
            raise ValueError
        parsed_as_of_date = datetime.strptime(as_of_date, "%Y-%m-%d")
        parsed_snapshot_date = datetime.strptime(snapshot_date, "%Y-%m-%d")
        dates_valid = parsed_as_of_date >= parsed_snapshot_date
    except ValueError:
        pass
    require(
        dates_valid,
        "canonical as_of_date must be an ISO date at or after verification execution",
        errors,
    )
    snapshot_boundary = verification_snapshot_boundary(
        snapshot_status,
        snapshot_date,
        errors,
    )
    if snapshot_boundary is None:
        return
    snapshot_policy = _text(snapshot.get("policy"))
    if snapshot_status == f"HISTORICAL_PRE_SOURCE_FREEZE_{snapshot_date}":
        require(
            snapshot_policy == historical_source_freeze_policy(snapshot_date),
            "historical verification snapshot must use the exact historical policy contract",
            errors,
        )
    elif snapshot_status == f"SOURCE_FREEZE_CANDIDATE_{snapshot_date}":
        require(
            snapshot_policy == candidate_source_freeze_policy(snapshot_date),
            "source-freeze candidate must use the exact candidate policy contract",
            errors,
        )
    elif snapshot_status == f"SOURCE_FREEZE_APPROVED_{snapshot_date}":
        require(
            snapshot_policy == APPROVED_SOURCE_FREEZE_POLICY,
            "approved source-freeze policy must match the exact approval contract",
            errors,
        )
    device_spec = validate_android_device_verification(snapshot, errors)
    if device_spec is not None:
        current = device_spec[0]
        historical = device_spec[1]
        snapshot_policy = _text(snapshot.get("policy"))
        require(
            all(
                marker in snapshot_policy
                for marker in (
                    str(current["status"]),
                    "historical evidence",
                    str(historical["executed_at"]),
                )
            ),
            "canonical verification policy must separate current status from historical device evidence",
            errors,
        )

    release_gates = root.get("release_gates")
    expected_gate_items = {
        "MODEL_DEPLOYMENT_QUALITY": _mapping(root.get("model_data")).get("blockers"),
        "MOBILE_FIELD_AND_ACCESSIBILITY": [
            "real_phone_camera_gps_mic_tts_haptic_accessibility",
            "outdoor_walking_task_evidence",
        ],
        "TACTILE_PLATFORM_FIELD_AND_SCOPE": [
            "web_camera_route_projection_supplier_missing",
            "web_camera_non_metric_real_phone_unverified",
            "android_camera_calibration_eis_unverified",
            "android_gps_drift_unverified",
            "android_tactile_local_steering_field_unverified",
            "android_arcore_unsupported_camera_non_metric_field_unverified",
        ],
        "PRODUCTION_OPERATIONS": [
            "production_iam_evidence_missing",
            "backup_restore_execution_evidence_missing",
            "retention_execution_evidence_missing",
        ],
        "INSTITUTION_SUBMISSION": ["institution_submission_receipt_missing"],
    }
    release_gate_records = (
        [gate for gate in release_gates if isinstance(gate, dict)]
        if isinstance(release_gates, list)
        else []
    )
    require(
        len(release_gate_records) == 5
        and [gate.get("id") for gate in release_gate_records] == list(expected_gate_items)
        and all(
            set(gate) == {"id", "status", "open_items", "evidence_required"}
            and gate.get("status") == "OPEN"
            and gate.get("open_items") == expected_gate_items[gate["id"]]
            and isinstance(gate.get("evidence_required"), str)
            and bool(gate["evidence_required"].strip())
            for gate in release_gate_records
        ),
        "canonical release_gates exact contract drifted",
        errors,
    )

    database = _mapping(root.get("database"))
    tables = database.get("tables")
    actual_table_roles = tuple(
        (table.get("name"), table.get("role"))
        for table in tables
        if isinstance(table, dict)
    ) if isinstance(tables, list) else ()
    require(
        actual_table_roles == EXPECTED_DATABASE_TABLE_ROLES,
        "canonical database must contain the exact five application tables and roles",
        errors,
    )
    rate_table = next(
        (
            table
            for table in tables
            if isinstance(table, dict) and table.get("name") == "actor_rate_limit_events"
        ),
        {},
    ) if isinstance(tables, list) else {}
    require(
        rate_table.get("window_seconds") == 60,
        "actor_rate_limit_events must remain a non-audit 60-second rate store",
        errors,
    )
    require(
        database.get("alembic_head") == "202607160001",
        "canonical Alembic head must be 202607160001",
        errors,
    )
    image_storage = _text(database.get("image_storage"))
    require(
        all(marker in image_storage for marker in ("durable journal", "startup reconciliation")),
        "canonical report storage must retain durable journal and startup reconciliation",
        errors,
    )

    runtime = _mapping(root.get("runtime"))
    security = _mapping(runtime.get("security"))
    security_markers = {
        "web_runtime": ("WALKSAFE_WEB_REPLICAS=1", "OS process lock"),
        "backend_database_transport": ("sslmode=verify-full", "gssencmode=disable", "loopback", "Unix socket"),
        "backend_rate_limit": (
            "actor_rate_limit_events",
            "worker·replica",
            "60초",
            "503",
        ),
        "backend_readiness": ("event loop", "daemon single-flight", "병렬", "timeout", "liveness", "프로세스 종료"),
    }
    for field, markers in security_markers.items():
        value = _text(security.get(field))
        require(
            all(marker in value for marker in markers),
            f"canonical runtime.security.{field} semantic contract drifted",
            errors,
        )

    privacy = _mapping(runtime.get("privacy"))
    expected_privacy_fields = {
        "web_frame_processing",
        "web_report_storage",
        "android_report_storage",
        "field_telemetry",
    }
    require(
        set(privacy) == expected_privacy_fields,
        "canonical privacy must keep four distinct consent scopes",
        errors,
    )
    privacy_markers = {
        "web_frame_processing": ("기본 off", "session-only", "server-v2"),
        "web_report_storage": ("기본 off", "session-only", "180일", "철회", "upload"),
        "android_report_storage": (
            "기본 off",
            "session-only",
            "GPS/Location이 제공한 이동 heading",
            "queued/in-flight",
        ),
        "field_telemetry": ("별도", "기본 off", "raw media", "철회", "in-flight"),
    }
    for field, markers in privacy_markers.items():
        value = _text(privacy.get(field))
        require(
            all(marker in value for marker in markers),
            f"canonical runtime.privacy.{field} semantic contract drifted",
            errors,
        )

    voice = _mapping(runtime.get("voice"))
    voice_markers = {
        "authentication": ("24자", "VOICE_SERVICE_TOKEN", "field/admin token"),
        "resource_limits": (
            "body copy 전에 auth·Content-Length·global/actor/IP rate",
            "copy하면서 chunk byte",
            "copy 후 audio duration",
            "inference queue",
            "disconnect",
        ),
        "topology": ("worker=1", "replica=1", "OS process lock"),
    }
    for field, markers in voice_markers.items():
        value = _text(voice.get(field))
        require(
            all(marker in value for marker in markers),
            f"canonical runtime.voice.{field} semantic contract drifted",
            errors,
        )

    api_contract = _mapping(runtime.get("api_contract"))
    api_markers = {
        "openapi": ("contracts/walksafe.openapi.json", "operation", "field/admin token", "actor ID·assertion"),
        "walking_route_fixture": ("contracts/fixtures/walking-route-v1.json", "TMAP", "Web·Android"),
        "provider": ("TMAP-only", "fallback하지 않는다"),
    }
    for field, markers in api_markers.items():
        value = _text(api_contract.get(field))
        require(
            all(marker in value for marker in markers),
            f"canonical runtime.api_contract.{field} semantic contract drifted",
            errors,
        )

    navigation = _mapping(runtime.get("navigation"))
    web_tactile = _mapping(navigation.get("web_supervised_tactile_local_steering"))
    require(
        web_tactile.get("status") == "DISABLED_PROJECTION_EVIDENCE_UNAVAILABLE"
        and "공급자가 없" in _text(web_tactile.get("fallback"))
        and "TMAP" in _text(web_tactile.get("fallback")),
        "canonical Web tactile supplier absence/fallback contract drifted",
        errors,
    )
    android_tactile = _mapping(navigation.get("android_tactile_local_steering"))
    require(
        android_tactile.get("status") == "WIRED_CODE_AUTO_FIELD_UNVERIFIED",
        "canonical Android tactile supplier status drifted",
        errors,
    )
    android_supplier = _text(android_tactile.get("supplier"))
    require(
        all(
            marker in android_supplier
            for marker in (
                "AndroidTactileRouteObservationSupplier",
                "ARCore physical camera pose",
                "동일 capture frame metric depth",
                "trusted GPS",
                "route ID·frame ID",
            )
        ),
        "canonical Android route/frame-bound supplier contract drifted",
        errors,
    )
    android_verification = _text(android_tactile.get("verification"))
    android_fallback = _text(android_tactile.get("fallback"))
    require(
        all(marker in android_verification for marker in ("JVM AUTO", "calibration/EIS", "FIELD 미검증"))
        and all(marker in android_fallback for marker in ("strict tactile policy gate", "TMAP", "즉시 복귀")),
        "canonical Android tactile verification/fallback contract drifted",
        errors,
    )
    require(
        all(
            marker in _text(navigation.get("limitation"))
            for marker in (
                "거리·보폭·STOP/high·local steering·안전 경로·경로 변경·자동 신고",
                "비계량 보조 경고",
                "trusted sensor_depth",
                "기존 Web stable bbox/ROI risk warning",
                "명시 동의 damaged-report",
            )
        )
        and "제품 선택 미결정" not in _text(navigation.get("limitation")),
        "canonical non-metric limitation or resolved unsupported-device decision drifted",
        errors,
    )
    non_metric = _mapping(navigation.get("camera_non_metric_advisory"))
    require(
        set(non_metric) == {
            "status",
            "platform_tiers",
            "route_authority",
            "risk_level",
            "directions",
            "stability",
            "platform_gates",
            "selection_policy",
            "delivery_recheck",
            "tactile_classes",
            "forbidden_outputs",
            "report_policy",
            "field_evidence",
        }
        and non_metric.get("status") == "WIRED_CODE_AUTO_FIELD_UNVERIFIED"
        and non_metric.get("risk_level") == "low"
        and non_metric.get("directions") == ["left", "center_or_front", "right"],
        "canonical non-metric advisory shape/status/directions drifted",
        errors,
    )
    require(
        _mapping(non_metric.get("platform_tiers")) == {
            "web": "CAMERA_NON_METRIC_ADVISORY",
            "android_arcore_supported": "ARCORE_METRIC",
            "android_arcore_unsupported": "CAMERA_IMU_NON_METRIC",
            "fallback": "TMAP_ONLY",
        }
        and "TMAP-only" in _text(non_metric.get("route_authority"))
        and "경로·조향 권한이 없다" in _text(non_metric.get("route_authority")),
        "canonical non-metric platform tier or TMAP authority drifted",
        errors,
    )
    require(
        _mapping(non_metric.get("stability")) == {
            "min_distinct_consecutive_frames": 3,
            "min_stable_ms": 700,
        },
        "canonical non-metric three-distinct-frame/700ms stability drifted",
        errors,
    )
    non_metric_platform_gates = _mapping(non_metric.get("platform_gates"))
    web_non_metric_gate = _text(non_metric_platform_gates.get("web"))
    android_non_metric_gate = _text(
        non_metric_platform_gates.get("android_arcore_unsupported")
    )
    require(
        set(non_metric_platform_gates) == {"web", "android_arcore_unsupported"}
        and all(
            marker in web_non_metric_gate
            for marker in (
                "후면 camera",
                "detector",
                "활성 TMAP",
                "foreground session",
                "page visibility",
                "fresh detection",
                "제공될 때 추가 gate",
                "미지원만으로 Web을 제외하지 않는다",
                "TMAP_ONLY",
            )
        )
        and re.search(r"IMU[^.]{0,40}(?:필수|반드시\s*요구)", web_non_metric_gate) is None
        and all(
            marker in android_non_metric_gate
            for marker in (
                "camera permission",
                "CameraX fallback",
                "detector",
                "fresh IMU",
                "활성 TMAP route",
                "TMAP_ONLY",
            )
        )
        and re.search(r"fresh\s+IMU[^.]{0,40}(?:선택|optional)", android_non_metric_gate, re.IGNORECASE)
        is None,
        "canonical platform-specific non-metric fallback gates drifted",
        errors,
    )
    require(
        all(
            marker in _text(non_metric.get("selection_policy"))
            for marker in (
                "기존 server-v2 risk evaluator",
                "alertable하지 않은 후보만",
                "stable bbox/ROI risk warning",
                "대체하거나 낮추지 않는다",
            )
        ),
        "canonical non-metric Web selection isolation drifted",
        errors,
    )
    require(
        all(
            marker in _text(non_metric.get("delivery_recheck"))
            for marker in ("전달 직전", "tier", "lifecycle", "freshness", "TTL", "폐기")
        ),
        "canonical non-metric delivery-time recheck drifted",
        errors,
    )
    require(
        _mapping(non_metric.get("tactile_classes")) == {
            "normal_tactile_block": "low advisory allowed",
            "damaged_tactile_block": "excluded; existing separate report-only path unchanged",
            "tactile_damage_area": "excluded; silent/report-only",
        }
        and non_metric.get("forbidden_outputs") == [
            "metric_distance_meters",
            "step_count_or_N_steps",
            "STOP",
            "high_risk",
            "local_steering",
            "safe_route_claim",
            "route_mutation",
            "auto_report",
        ],
        "canonical non-metric tactile/forbidden-output boundary drifted",
        errors,
    )
    require(
        _mapping(non_metric.get("report_policy")) == {
            "degraded_advisory_reports": False,
            "android_limited_mode_reports": False,
            "web_existing_explicit_consent_server_v2_damaged_report_pipeline": "unchanged",
        }
        and _mapping(non_metric.get("field_evidence")) == {
            "web_real_phone": "UNVERIFIED_OPEN",
            "android_arcore_unsupported_device": "UNVERIFIED_OPEN",
        },
        "canonical non-metric report isolation or Field evidence boundary drifted",
        errors,
    )


def find_forbidden_source_semantics(text: str) -> list[str]:
    return [
        pattern.pattern
        for pattern in SOURCE_SEMANTIC_FORBIDDEN_PATTERNS
        if pattern.search(text)
    ]


def validate_submission_source_semantics(repository_root: Path, errors: list[str]) -> None:
    """Validate canonical facts and source manuscripts before generated-file checks."""
    facts_path = repository_root / "docs/submission/form_materials/09_제출_사실_기준.json"
    if not facts_path.is_file() or facts_path.is_symlink():
        errors.append("missing or unsafe canonical facts for source semantics")
        return
    try:
        facts = json.loads(facts_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"invalid canonical facts for source semantics: {exc}")
        return
    validate_core_semantic_facts(facts, errors)

    source_texts: dict[str, str] = {}
    for relative, markers in SOURCE_SEMANTIC_MARKERS.items():
        path = repository_root / relative
        if not path.is_file() or path.is_symlink():
            errors.append(f"missing or unsafe semantic source: {relative}")
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            errors.append(f"unreadable semantic source {relative}: {exc}")
            continue
        source_texts[relative] = text
        missing = [marker for marker in markers if marker not in text]
        if missing:
            errors.append(
                f"semantic source drift in {relative}: missing {', '.join(missing)}"
            )
    scan_paths = {repository_root / relative for relative in SOURCE_SEMANTIC_MARKERS}
    for pattern in SOURCE_SEMANTIC_SCAN_GLOBS:
        scan_paths.update(repository_root.glob(pattern))
    for path in sorted(scan_paths):
        relative = path.relative_to(repository_root).as_posix()
        text = source_texts.get(relative)
        if text is None:
            if not path.is_file() or path.is_symlink():
                errors.append(f"missing or unsafe semantic scan source: {relative}")
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                errors.append(f"unreadable semantic scan source {relative}: {exc}")
                continue
        forbidden = find_forbidden_source_semantics(text)
        if forbidden:
            errors.append(
                f"stale semantic assertion in {relative}: {', '.join(forbidden)}"
            )


def validate_evidence_results_semantics(repository_root: Path, errors: list[str]) -> None:
    facts_path = repository_root / "docs/submission/form_materials/09_제출_사실_기준.json"
    sidecar_path = repository_root / "docs/submission/form_materials/assets/evidence_results.semantic.json"
    image_path = repository_root / "docs/submission/form_materials/assets/evidence_results.png"
    for path, label in (
        (facts_path, "canonical facts"),
        (sidecar_path, "evidence semantic sidecar"),
        (image_path, "evidence results image"),
    ):
        if not path.is_file() or path.is_symlink():
            errors.append(f"missing or unsafe {label}: {path.relative_to(repository_root)}")
            return

    try:
        facts = json.loads(facts_path.read_text(encoding="utf-8"))
        sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"invalid evidence semantic input: {exc}")
        return

    require(
        facts.get("schema_version") == "walksafe.submission_facts.v2",
        "unexpected canonical facts schema for evidence semantics",
        errors,
    )
    require(
        sidecar.get("schema_version") == "walksafe.evidence-results-semantics.v1",
        "unexpected evidence semantic sidecar schema",
        errors,
    )
    snapshot = facts.get("verification_snapshot")
    counts = snapshot.get("counts") if isinstance(snapshot, dict) else None
    expected_count_keys = {
        "unit_python",
        "functional_python",
        "integration_python",
        "backend_full",
        "voice",
        "android_jvm",
        "web_trace_count",
        "web_trace_files",
    }
    if (
        not isinstance(counts, dict)
        or set(counts) != expected_count_keys
        or any(isinstance(value, bool) or not isinstance(value, int) or value < 1 for value in counts.values())
    ):
        errors.append("invalid canonical verification count contract for evidence semantics")
        return
    snapshot_status = snapshot.get("status") if isinstance(snapshot, dict) else None
    snapshot_date = snapshot.get("executed_at") if isinstance(snapshot, dict) else None
    if not isinstance(snapshot_status, str) or not snapshot_status or not isinstance(snapshot_date, str):
        errors.append("canonical verification snapshot must declare status and executed_at")
        return
    device_spec = validate_android_device_verification(snapshot, errors)
    if device_spec is None:
        return
    current_device, historical_device, _, _ = device_spec
    current_device_text = "현재 source-freeze: NOT RUN"
    historical_device_text = (
        f"과거 evidence {historical_device['executed_at']}\n"
        f"{historical_device['device']} · instrumentation "
        f"{historical_device['passed']}/{historical_device['total']} PASS"
    )
    snapshot_boundary = verification_snapshot_boundary(
        snapshot_status,
        snapshot_date,
        errors,
    )
    if snapshot_boundary is None:
        return
    snapshot_scope = f"{snapshot_date} {snapshot_boundary}"

    expected_facts = {
        "path": "docs/submission/form_materials/09_제출_사실_기준.json",
        "schema_version": "walksafe.submission_facts.v2",
        "sha256": sha256(facts_path),
    }
    require(
        sidecar.get("facts") == expected_facts,
        "evidence semantic sidecar is stale against canonical facts",
        errors,
    )
    try:
        with Image.open(image_path) as image:
            image.verify()
        with Image.open(image_path) as image:
            image_size = image.size
            embedded_rendered_fields = json.loads(
                image.info.get(EVIDENCE_RESULTS_PNG_SEMANTIC_KEY, "null")
            )
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"invalid evidence results image: {exc}")
        return
    expected_artifact = {
        "path": "docs/submission/form_materials/assets/evidence_results.png",
        "sha256": sha256(image_path),
        "width": image_size[0],
        "height": image_size[1],
    }
    require(
        sidecar.get("artifact") == expected_artifact,
        "evidence semantic sidecar is not bound to the rendered PNG",
        errors,
    )

    trace_text = f"NFT {counts['web_trace_count']:,} traces/{counts['web_trace_files']:,} files"
    expected_rendering = {
        "unit_python": ("policy_unit", f"Python selected unit {counts['unit_python']:,}"),
        "android_jvm": (
            "policy_unit",
            f"Android JVM {counts['android_jvm']:,}/{counts['android_jvm']:,}",
        ),
        "functional_python": ("backend_db", f"PostGIS functional {counts['functional_python']:,}"),
        "integration_python": ("backend_db", f"integration {counts['integration_python']:,}"),
        "backend_full": ("backend_db", f"Backend full {counts['backend_full']:,}"),
        "web_trace_count": ("web_voice_build", trace_text),
        "web_trace_files": ("web_voice_build", trace_text),
        "voice": ("web_voice_build", f"Voice {counts['voice']:,}"),
    }
    expected_fields = [
        {
            "fact_path": "verification_snapshot.status",
            "card_id": "snapshot_scope",
            "value": snapshot_status,
            "rendered_text": snapshot_scope,
        },
        {
            "fact_path": "verification_snapshot.executed_at",
            "card_id": "snapshot_scope",
            "value": snapshot_date,
            "rendered_text": snapshot_scope,
        },
        *[
            {
                "fact_path": f"verification_snapshot.counts.{key}",
                "card_id": card_id,
                "value": counts[key],
                "rendered_text": rendered_text,
            }
            for key, (card_id, rendered_text) in expected_rendering.items()
        ],
        *[
            {
                "fact_path": (
                    f"verification_snapshot.android_device_verification.{section}.{key}"
                ),
                "card_id": "android_device",
                "value": record[key],
                "rendered_text": (
                    current_device_text
                    if section == "current_source_freeze"
                    else historical_device_text
                ),
            }
            for section, record, keys in (
                (
                    "current_source_freeze",
                    current_device,
                    ("status", "executed_at", "passed", "total"),
                ),
                (
                    "historical_evidence",
                    historical_device,
                    ("status", "executed_at", "device", "scope", "passed", "total"),
                ),
            )
            for key in keys
        ],
    ]
    require(
        sidecar.get("rendered_fields") == expected_fields,
        "evidence semantic rendered fields differ from canonical facts",
        errors,
    )
    require(
        embedded_rendered_fields == expected_fields,
        "evidence PNG embedded semantics differ from canonical facts",
        errors,
    )


def validate_asset_build_manifest(errors: list[str]) -> None:
    manifest_path = ASSETS / "BUILD_MANIFEST.json"
    if not manifest_path.is_file():
        errors.append("missing asset build manifest: assets/BUILD_MANIFEST.json")
        return
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        errors.append(f"invalid asset build manifest: {exc}")
        return
    require(
        manifest.get("schema_version") == "walksafe.submission-assets-build.v1",
        "unexpected asset build manifest schema",
        errors,
    )
    require(
        manifest.get("allowed_asset_files") == sorted(ASSET_DIRECTORY_FILES),
        "asset build allowed file set mismatch",
        errors,
    )
    revision = source_revision(REPO_ROOT, ALL_SUBMISSION_GENERATED_PATHS)
    require(revision.get("source_dirty") is False, "current submission source is dirty", errors)
    require(manifest.get("source_dirty") is False, "asset build manifest records dirty source", errors)
    require(
        all(manifest.get(key) == value for key, value in revision.items()),
        "asset build source revision mismatch",
        errors,
    )
    require(
        manifest.get("build_tools") == build_tool_provenance(("Pillow",)),
        "asset build tool provenance mismatch",
        errors,
    )
    try:
        expected_toolchain = submission_toolchain_attestation(REPO_ROOT)
    except ValueError as exc:
        errors.append(str(exc))
        expected_toolchain = None
    require(
        expected_toolchain is not None and manifest.get("submission_toolchain") == expected_toolchain,
        "asset build submission toolchain mismatch",
        errors,
    )

    verified_inputs: list[dict[str, object]] = []
    sections = (
        ("repository_inputs", ASSET_REPOSITORY_INPUT_PATHS, REPO_ROOT),
        ("system_inputs", ASSET_SYSTEM_INPUT_PATHS, Path("/")),
        ("artifacts", ASSET_ARTIFACT_PATHS, REPO_ROOT),
    )
    for section, expected_paths, base in sections:
        try:
            entries = require_exact_manifest_paths(section, manifest.get(section), expected_paths)
        except ValueError as exc:
            errors.append(str(exc))
            continue
        for entry in entries:
            path_value = str(entry["path"])
            path = Path(path_value) if Path(path_value).is_absolute() else base / path_value
            exists = path.is_file() and not path.is_symlink()
            require(exists, f"asset manifest file missing or unsafe: {path_value}", errors)
            if not exists:
                continue
            require(
                sha256(path) == entry.get("sha256") and path.stat().st_size == entry.get("bytes"),
                f"asset manifest hash/size mismatch: {path_value}",
                errors,
            )
            if section in {"repository_inputs", "system_inputs"}:
                verified_inputs.append(entry)
    actual_bundle = (
        record_bundle_sha256(verified_inputs)
        if len(verified_inputs)
        == len(ASSET_REPOSITORY_INPUT_PATHS) + len(ASSET_SYSTEM_INPUT_PATHS)
        else "incomplete"
    )
    require(
        actual_bundle == manifest.get("input_bundle_sha256"),
        "asset build input bundle SHA-256 mismatch",
        errors,
    )


def main() -> int:
    errors: list[str] = []

    validate_submission_source_semantics(REPO_ROOT, errors)
    revision = source_revision(REPO_ROOT, ALL_SUBMISSION_GENERATED_PATHS)
    require(revision.get("source_dirty") is False, "current submission source is dirty", errors)

    expected_material_files = {
        relative for relative in REQUIRED_MATERIALS if "/" not in relative
    }
    expected_asset_files = set(ASSET_DIRECTORY_FILES)
    try:
        require_exact_directory_entries(MATERIALS, expected_material_files, {"assets"})
        require_exact_file_set(ASSETS, expected_asset_files)
    except (FileNotFoundError, ValueError) as exc:
        errors.append(str(exc))

    for relative in REQUIRED_MATERIALS:
        path = MATERIALS / relative
        require(path.is_file() and path.stat().st_size > 0, f"missing material: {relative}", errors)

    for relative, expected_hash in EXPECTED_TEMPLATE_HASHES.items():
        path = REPO_ROOT / relative
        require(path.is_file(), f"missing original template: {relative}", errors)
        if path.is_file():
            require(sha256(path) == expected_hash, f"original template changed: {relative}", errors)

    for name, expected_size in EXPECTED_ASSETS.items():
        path = ASSETS / name
        require(path.is_file(), f"missing asset: {name}", errors)
        if not path.is_file():
            continue
        with Image.open(path) as image:
            require(image.size == expected_size, f"asset size mismatch: {name}={image.size}", errors)

    asset_readme = (ASSETS / "README.md").read_text(encoding="utf-8")
    require(
        re.search(r"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])", asset_readme, re.IGNORECASE)
        is None,
        "evergreen asset README embeds a build-specific 64-hex hash",
        errors,
    )
    require(
        "Web/Android TMAP-only" not in asset_readme,
        "asset README combines stale Web/Android tactile supplier semantics",
        errors,
    )
    for marker in (
        "BUILD_MANIFEST.json",
        "manifest repository input",
        "generated artifact manifest",
        "evidence_results.semantic.json",
        "Web supplier 부재",
        "Android route/frame-bound supplier",
    ):
        require(marker in asset_readme, f"asset README missing evergreen binding marker: {marker}", errors)

    validate_evidence_results_semantics(REPO_ROOT, errors)
    validate_asset_build_manifest(errors)

    report_text = (MATERIALS / "04_개발보고서_본문원고.md").read_text(encoding="utf-8")
    slides_text = (MATERIALS / "05_제작설계서_34장_원고.md").read_text(encoding="utf-8")
    facts = json.loads((MATERIALS / "09_제출_사실_기준.json").read_text(encoding="utf-8"))
    require(facts.get("schema_version") == "walksafe.submission_facts.v2", "unexpected facts schema", errors)
    require(len(facts.get("requirements", [])) == 14, "facts must contain 14 requirements", errors)
    require(len(facts.get("use_cases", [])) == 9, "facts must contain 9 use cases", errors)
    require(
        all(item.get("status") == "PARTIAL" for item in [*facts.get("requirements", []), *facts.get("use_cases", [])]),
        "all REQ/UC statuses must remain PARTIAL",
        errors,
    )

    for heading in (
        "# I. 프로젝트 개요",
        "# II. 프로젝트 내용",
        "# III. 프로젝트 수행 내용",
        "# Ⅳ. 기대효과 및 활용분야",
    ):
        require(heading in report_text, f"missing official report heading: {heading}", errors)

    slide_numbers = [int(value) for value in re.findall(r"^## (\d+)\.", slides_text, flags=re.MULTILINE)]
    require(slide_numbers == list(range(1, 35)), f"slide headings are not 1..34: {slide_numbers}", errors)

    for item in facts["requirements"]:
        require(
            item["id"] in slides_text and item["title"] in slides_text,
            f"canonical requirement mismatch: {item['id']} {item['title']}",
            errors,
        )
    for item in facts["use_cases"]:
        require(
            item["id"] in slides_text and item["title"] in slides_text,
            f"canonical use-case mismatch: {item['id']} {item['title']}",
            errors,
        )

    forbidden = {
        "자동/수동 신고": "v2 manual-button wording",
        "Office 도형으로 직접 작성": "diagram not materialized first",
        "val_batch0_pred.jpg": "privacy-risk sample",
        "R01": "non-canonical requirement ID",
    }
    checked_text = report_text + "\n" + slides_text
    for phrase, reason in forbidden.items():
        require(phrase not in checked_text, f"forbidden phrase ({reason}): {phrase}", errors)

    for required in (
        "Web/PWA",
        "Android는 ARCore",
        "기관 API",
        "현재 필터 최대 10,000행",
        "2,331",
        "PARTIAL",
        "report_status_audits",
        "report_export_audits",
        "report_read_audits",
        "actor_rate_limit_events",
        "durable journal",
        "startup reconciliation",
        "sslmode=verify-full",
        "gssencmode=disable",
        "VOICE_SERVICE_TOKEN",
        "OpenAPI",
        "TMAP-only",
        "server-v2 처리 동의",
        "180일 저장 동의",
        "duplicate_count",
        "network-only",
        "배포 적격",
    ):
        require(required in checked_text, f"missing scope marker: {required}", errors)

    for phrase in facts["forbidden_claims"]:
        require(phrase not in checked_text, f"canonical forbidden claim found: {phrase}", errors)

    apk_literal = re.compile(
        r"(?:app|AndroidTest)\s+APK[^\n]{0,180}(?:"
        r"\d{1,3}(?:,\d{3})+\s*bytes|SHA-?256\s*`?[0-9a-f]{12,64}|`[0-9a-f]{12,64}…?`)",
        re.IGNORECASE,
    )
    for source in sorted(MATERIALS.glob("*.md")):
        require(
            apk_literal.search(source.read_text(encoding="utf-8")) is None,
            f"evergreen material embeds a build-specific APK size/hash; use the generated release receipt: {source.name}",
            errors,
        )

    if errors:
        for error in errors:
            print(f"[FAIL] {error}")
        return 1

    print(
        "submission materials PASS: "
        f"{len(REQUIRED_MATERIALS)} documents, {len(EXPECTED_ASSETS)} assets, "
        "34 slides, REQ-001..014, UC-01..09, original templates unchanged"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
