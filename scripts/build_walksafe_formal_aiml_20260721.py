#!/usr/bin/env python3
"""Build the controlled WalkSafe AIML Draft bundle.

The builder turns approved policy into authoring-ready plans, cards,
schemas, registers, and protocols.  Existing data, model, code, and
configuration are recorded only as candidates.  It deliberately does not
invent data-quality, split, experiment, evaluation, conversion-equivalence,
device-performance, or production-monitoring results.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable

try:
    from scripts import build_walksafe_control_bootstrap as control_builder
    from scripts import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    from scripts import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder
except ModuleNotFoundError:
    import build_walksafe_control_bootstrap as control_builder
    import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"
AIML_DIR = DELIVERABLES_DIR / "08-ai-ml-data"
REGISTERS_DIR = AIML_DIR / "registers"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
APPROVAL_RECORD_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
BASELINE_MANIFEST_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
ARTIFACT_CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
ALIGNED_DECISION_REGISTER_PATH = CONTROL_DIR / "decision-interview" / "walksafe-effective-decision-register-aligned-20260721-r001.json"
FP035_CORRECTION_CANDIDATE_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"

DATA_DOCUMENT_PATH = AIML_DIR / "data-management.md"
MODEL_DOCUMENT_PATH = AIML_DIR / "model-development.md"
EVALUATION_DOCUMENT_PATH = AIML_DIR / "model-evaluation.md"
OPERATIONS_DOCUMENT_PATH = AIML_DIR / "model-operations.md"
DATA_SOURCE_REGISTER_PATH = REGISTERS_DIR / "data-source-register.json"
DATASET_REGISTER_PATH = REGISTERS_DIR / "dataset-register.json"
MODEL_REGISTER_PATH = REGISTERS_DIR / "model-register.json"
EXPERIMENT_REGISTER_PATH = REGISTERS_DIR / "experiment-register.json"
EVALUATION_REGISTER_PATH = REGISTERS_DIR / "evaluation-evidence-register.json"
OPERATIONS_REGISTER_PATH = REGISTERS_DIR / "model-operations-register.json"
MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "aiml-draft-20260721-r001.json"

MODEL_SOURCE_REGISTRY_PATH = REPO_ROOT / "model" / "registry" / "walksafe-model-registry.json"
DEPLOYMENT_SOURCE_PATH = REPO_ROOT / "model" / "deployments" / "local-deployment.json"
RUNTIME_CONFIG_PATH = REPO_ROOT / "apps" / "android" / "app" / "src" / "main" / "assets" / "model-config" / "two_model_runtime.json"
ANDROID_ASSETS_DIR = REPO_ROOT / "apps" / "android" / "app" / "src" / "main" / "assets"
TWO_MODEL_CLASS_MAP_PATH = (
    REPO_ROOT
    / "apps"
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "kr"
    / "co"
    / "hanium"
    / "dreamup"
    / "walksafe"
    / "inference"
    / "TwoModelClassMap.kt"
)

RUNTIME_MODEL_IDS = ("unified_walksafe", "custom_tactile", "coco_general")
RUNTIME_CLASS_NAMESPACES = {
    "unified_walksafe": "walksafe.unified_walksafe.class_id",
    "custom_tactile": "walksafe.custom_tactile.class_id",
    "coco_general": "coco.coco_general.class_id",
}

AS_OF = "2026-07-21"
VERSION = "0.1.0"
LIFECYCLE_STATUS = "DRAFT"
RELEASE_STATUS = "NOT_ELIGIBLE"
POLICY_CONTENT_SHA256 = "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28"
DECISION_BINDING_SHA256 = "16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538"
FP035_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_NORMALIZATION_STATUS = "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
FP035_NORMALIZED_POLICY = {
    "walking": "일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다.",
    "stopped_mobile_opted_in": "사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.",
    "stopped_mobile_not_opted_in": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
}
FP035_NORMATIVE_RULE = "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."

SCOPE_IDS = [f"AIML-{number:02d}" for number in range(1, 27)]
MATERIALIZED_IDS = [
    "AIML-01", "AIML-02", "AIML-03", "AIML-04", "AIML-06", "AIML-07",
    "AIML-12", "AIML-14", "AIML-15", "AIML-16", "AIML-17",
    "AIML-24", "AIML-25", "AIML-26",
]
PLANNED_IDS = [
    "AIML-05", "AIML-08", "AIML-09", "AIML-10", "AIML-11", "AIML-13", "AIML-18",
    "AIML-19", "AIML-20", "AIML-21", "AIML-22", "AIML-23",
]

PLANNED_EXECUTION_CONTRACTS = {
    "AIML-05": {
        "when": "학습·검증·시험 데이터셋 버전을 승인하기 전과 자료구성이 바뀔 때",
        "executor": "데이터책임자",
        "prerequisites": ["불변 dataset manifest와 파일 지문", "출처·권리·동의 상태", "승인된 품질항목과 표본추출 방법"],
        "evidence": ["전체·출처·class별 수량과 결측·중복·손상 통계", "검사 도구·설정·실행 log", "제외 목록과 조치·재검사 결과"],
        "judgment": "사전에 정한 품질항목을 모두 계산하고 중대한 결측·손상·권리 미확인이 0건이며 남은 한계가 승인돼야 PASS",
    },
    "AIML-08": {
        "when": "라벨 묶음을 학습 또는 독립 평가에 사용하기 전",
        "executor": "라벨링 품질검사자",
        "prerequisites": ["AIML-07 지침 version", "가명화된 표본 목록", "검사자와 라벨 작성자의 역할 구분"],
        "evidence": ["class·난이도별 표본", "일치·누락·경계 오류 집계", "불일치 조정 기록과 수정 version"],
        "judgment": "승인된 표본수와 오류기준을 충족하고 치명적 class 혼동·누락을 모두 조치한 경우만 PASS",
    },
    "AIML-09": {
        "when": "학습 실행을 시작하기 전과 dataset version이 바뀔 때",
        "executor": "데이터 엔지니어",
        "prerequisites": ["불변 sample·capture_group·source ID", "분할 비율과 독립 시험 잠금 규칙"],
        "evidence": ["train·validation·test manifest", "각 manifest SHA-256", "group 겹침 0건 검사 결과"],
        "judgment": "동일 보행·연속촬영·원본 파생물이 분할 사이에 겹치지 않고 독립 시험 묶음이 잠겨야 PASS",
    },
    "AIML-10": {
        "when": "분할 뒤, 학습 전, 그리고 평가 결과를 승인하기 전",
        "executor": "ML 검증책임자",
        "prerequisites": ["AIML-09 분할 manifest", "원본·파생·중복을 찾을 content/group key"],
        "evidence": ["정확·유사·group 중복 검사 원자료", "누수 후보별 판정", "재분할·재실행 기록"],
        "judgment": "확정 누수 0건이고 모든 후보의 근거 있는 판정과 재검사가 끝나야 PASS",
    },
    "AIML-11": {
        "when": "정식 학습마다 실행 전에 고정하고 종료 직후 기록",
        "executor": "ML 개발책임자",
        "prerequisites": ["승인 dataset·split hash", "학습 코드 commit", "환경·의존성 lock", "설정·seed"],
        "evidence": ["실행 ID와 명령·설정", "환경·GPU·도구 version", "stdout·metric 원자료", "결과 모델·checkpoint SHA-256"],
        "judgment": "입력·코드·환경·seed와 결과 지문이 한 실행으로 결속되고 같은 절차의 재실행 가능성을 검토해야 완료",
    },
    "AIML-13": {
        "when": "새 모델 후보를 승격하기 전",
        "executor": "ML 검증책임자",
        "prerequisites": ["같은 독립 시험 split", "현재 승인본 또는 명시한 단순 기준모델", "AIML-17 평가 protocol"],
        "evidence": ["후보별 model hash", "동일 입력의 전체 지표", "차이와 통계적 불확실성", "퇴행 목록"],
        "judgment": "필수 안전지표 퇴행이 없고 승인된 개선조건을 충족해야 PASS; 일부 평균 개선으로 치명적 class 퇴행을 상쇄하지 않음",
    },
    "AIML-18": {
        "when": "모델 후보·dataset·평가 protocol 중 하나가 바뀔 때마다",
        "executor": "독립 평가책임자",
        "prerequisites": ["잠긴 독립 test split", "평가 대상 model·runtime hash", "AIML-17 protocol"],
        "evidence": ["전체·class·거리·환경별 원지표", "confusion 자료", "평가 명령·환경·원출력 SHA-256"],
        "judgment": "사전 등록한 모든 필수 구간과 지표가 누락 없이 계산되고 기준을 충족해야 PASS",
    },
    "AIML-19": {
        "when": "AIML-18 평가 뒤와 중대한 현장 결함이 생길 때",
        "executor": "ML 안전분석자",
        "prerequisites": ["예측·정답 연결 결과", "class·거리·환경·위해도 분류규칙"],
        "evidence": ["오탐·미탐 사례 묶음", "원인 taxonomy와 빈도", "안전 영향·완화·회귀시험 연결"],
        "judgment": "치명적 사례를 모두 분류·조치하거나 명시적으로 출시 차단하고 잔여위험을 승인해야 종결",
    },
    "AIML-20": {
        "when": "출시 후보 평가와 대상 환경·사용자 범위 변경 시",
        "executor": "독립 안전·편향 검토자",
        "prerequisites": ["대표 조도·날씨·기기·장착·지역 구간", "위해 시나리오와 중단기준"],
        "evidence": ["구간별 강건성·편차 결과", "최악 구간", "공격·손상·범위 밖 입력 결과", "제한·완화 기록"],
        "judgment": "중대한 안전 격차가 기준 안이거나 지원범위 제한·차단으로 통제되고 독립 검토가 완료돼야 PASS",
    },
    "AIML-21": {
        "when": "학습 모델을 TFLite 등 배포 형식으로 변환할 때마다",
        "executor": "ML·Android 통합책임자",
        "prerequisites": ["원본·변환 모델 hash", "고정 동등성 입력 묶음", "정확한 전처리·후처리 설정"],
        "evidence": ["샘플별 원출력과 배포출력", "허용 오차·불일치 통계", "변환 명령·도구 version", "재변환 결과"],
        "judgment": "사전 허용오차와 class·순서·shape 계약을 모두 충족하고 치명적 판정 불일치가 0건이어야 PASS",
    },
    "AIML-22": {
        "when": "지원 Android 기기 후보와 release model 조합마다 베타 전",
        "executor": "Android 성능시험자",
        "prerequisites": ["APK·model·config hash", "기기·OS·전원·온도·장착 조건", "warmup·반복 protocol"],
        "evidence": ["지연 percentile·FPS·memory", "배터리·온도·throttling 원자료", "기기별 log와 결함"],
        "judgment": "지원 기기별 사전 성능·발열·배터리 기준을 충족해야 PASS; Web/PWA는 별도 재승인 전 제외",
    },
    "AIML-23": {
        "when": "모델·거리계산·위험단계·지원범위 중 하나가 바뀔 때",
        "executor": "ML·안전책임자",
        "prerequisites": ["AIML-18·19 결과", "거리·class별 위해 비용", "FP-020 행동단계 계약"],
        "evidence": ["후보 임계값 sweep", "오탐·미탐 trade-off", "선정 근거·버전", "독립 재평가 결과"],
        "judgment": "평균점수만이 아니라 거리·class별 안전비용을 충족하고 선정값이 앱 설정 hash에 결속돼야 PASS",
    },
}

DOCUMENT_SCOPE = {
    DATA_DOCUMENT_PATH: {
        "bundle_id": "BND-AIML-DATA",
        "title": "WalkSafe 데이터 관리·데이터셋 통제",
        "all_ids": [f"AIML-{number:02d}" for number in range(1, 11)],
        "draft_ids": ["AIML-01", "AIML-02", "AIML-03", "AIML-04", "AIML-06", "AIML-07"],
    },
    MODEL_DOCUMENT_PATH: {
        "bundle_id": "BND-AIML-MODEL",
        "title": "WalkSafe 모델 개발·등록 통제",
        "all_ids": [f"AIML-{number:02d}" for number in range(11, 17)],
        "draft_ids": ["AIML-12", "AIML-14", "AIML-15", "AIML-16"],
    },
    EVALUATION_DOCUMENT_PATH: {
        "bundle_id": "BND-AIML-EVALUATION",
        "title": "WalkSafe 모델 평가 통제",
        "all_ids": [f"AIML-{number:02d}" for number in range(17, 24)],
        "draft_ids": ["AIML-17"],
    },
    OPERATIONS_DOCUMENT_PATH: {
        "bundle_id": "BND-AIML-OPS",
        "title": "WalkSafe 모델 배포·운영 통제",
        "all_ids": [f"AIML-{number:02d}" for number in range(24, 27)],
        "draft_ids": ["AIML-24", "AIML-25", "AIML-26"],
    },
}

FILE_DRAFT_COVERAGE = {
    DATA_DOCUMENT_PATH: DOCUMENT_SCOPE[DATA_DOCUMENT_PATH]["draft_ids"],
    MODEL_DOCUMENT_PATH: DOCUMENT_SCOPE[MODEL_DOCUMENT_PATH]["draft_ids"],
    EVALUATION_DOCUMENT_PATH: DOCUMENT_SCOPE[EVALUATION_DOCUMENT_PATH]["draft_ids"],
    OPERATIONS_DOCUMENT_PATH: DOCUMENT_SCOPE[OPERATIONS_DOCUMENT_PATH]["draft_ids"],
    DATA_SOURCE_REGISTER_PATH: ["AIML-03"],
    DATASET_REGISTER_PATH: ["AIML-02", "AIML-04"],
    MODEL_REGISTER_PATH: ["AIML-14", "AIML-16"],
    EXPERIMENT_REGISTER_PATH: ["AIML-12"],
    EVALUATION_REGISTER_PATH: [],
    OPERATIONS_REGISTER_PATH: [],
}

DATA_SOURCE_CANDIDATES = [
    ("SRC-CANDIDATE-WALKSAFE-V1", "data_sources/manifests/walksafe_v1_sources.md", "공개 baseline 후보의 출처 설명"),
    ("SRC-CANDIDATE-KR-V3-POLICY", "data_sources/manifests/walksafe_kr_v3_policy_2026-05-19.md", "한국 환경 v3 후보의 보류·포함 정책"),
    ("SRC-CANDIDATE-KR-V3-INDEX", "data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv", "v3 후보 행 인덱스"),
    ("SRC-CANDIDATE-KR-V3-SUMMARY", "data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json", "v3 후보 인덱스 요약"),
    ("SRC-CANDIDATE-CURATION", "data_sources/manifests/walksafe_kr_v3_curation_manifest_2026-05-18.csv", "과거 수동 선별 후보"),
    ("SRC-CANDIDATE-RELABEL-CLASSES", "ai_tasks/walksafe_v3_relabel_20260521/classes.txt", "과거 재라벨 작업의 class 후보"),
]

MODEL_CODE_CANDIDATES = [
    "model/train_yolo.py",
    "model/validate_yolo_dataset.py",
    "model/sample_yolo_failures.py",
    "scripts/export_walksafe_unified_tflite_20260601.py",
    "scripts/check_android_apk_model_asset_20260713.py",
]


class FormalAIMLError(ValueError):
    """Raised when controlled inputs or generated AIML outputs are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise FormalAIMLError(message)


def _reject_constant(value: str) -> None:
    raise FormalAIMLError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


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
        raise FormalAIMLError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _object_sha256(value: Any) -> str:
    return _sha256_bytes(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source_binding(path: Path) -> dict[str, str]:
    return {"path": _rel(path), "sha256": _sha256_file(path)}


def _kotlin_string_collection(source: str, variable_name: str) -> list[str]:
    match = re.search(
        rf"\bval\s+{re.escape(variable_name)}\s*=\s*(?:listOf|setOf)\((.*?)\n\s*\)",
        source,
        flags=re.DOTALL,
    )
    _require(match is not None, f"Kotlin class collection missing: {variable_name}")
    return re.findall(r'"([^"]+)"', match.group(1))


def _canonical_class_name(runtime_label: str) -> str:
    canonical = re.sub(r"[^a-z0-9]+", "_", runtime_label.strip().lower()).strip("_")
    _require(bool(canonical), f"empty canonical class name: {runtime_label!r}")
    return canonical


def _runtime_class_schemas(runtime: dict[str, Any]) -> dict[str, dict[str, Any]]:
    models = runtime.get("models", {})
    _require(tuple(models) == RUNTIME_MODEL_IDS, "runtime model ID order or exact set differs")

    kotlin_source = TWO_MODEL_CLASS_MAP_PATH.read_text(encoding="utf-8")
    kotlin_orders = {
        "unified_walksafe": (
            _kotlin_string_collection(kotlin_source, "unifiedCocoClasses")
            + _kotlin_string_collection(kotlin_source, "unifiedTactileClasses")
        ),
        "custom_tactile": _kotlin_string_collection(kotlin_source, "customTactileClasses"),
        "coco_general": _kotlin_string_collection(kotlin_source, "cocoClasses"),
    }
    expected_counts = {"unified_walksafe": 13, "custom_tactile": 3, "coco_general": 80}
    schemas: dict[str, dict[str, Any]] = {}
    for model_id in RUNTIME_MODEL_IDS:
        runtime_order = models[model_id].get("classes", [])
        _require(runtime_order == kotlin_orders[model_id], f"runtime/Kotlin class order differs: {model_id}")
        _require(len(runtime_order) == expected_counts[model_id], f"runtime class count differs: {model_id}")
        canonical_names = [_canonical_class_name(label) for label in runtime_order]
        _require(len(canonical_names) == len(set(canonical_names)), f"canonical class collision: {model_id}")
        namespace = RUNTIME_CLASS_NAMESPACES[model_id]
        schemas[model_id] = {
            "schema_id": f"WS-RUNTIME-CLASS-SCHEMA-{model_id.upper()}",
            "schema_version": "1.0.0",
            "approval_status": "NOT_APPROVED",
            "runtime_model_id": model_id,
            "class_id_namespace": namespace,
            "class_count": len(runtime_order),
            "class_order": runtime_order,
            "class_order_sha256": _object_sha256(runtime_order),
            "classes": [
                {
                    "class_id": class_id,
                    "qualified_class_id": f"{namespace}:{class_id}",
                    "runtime_label": label,
                    "canonical_name": canonical,
                }
                for class_id, (label, canonical) in enumerate(zip(runtime_order, canonical_names, strict=True))
            ],
            "canonical_name_conversion": {
                "algorithm": "trim, lowercase ASCII, replace each non-alphanumeric run with underscore, trim edge underscores",
                "cross_model_numeric_id_equivalence": False,
            },
            "breaking_changes": {
                "class_addition": "BREAKING_MAJOR",
                "class_deletion": "BREAKING_MAJOR",
                "class_reorder": "BREAKING_MAJOR",
                "required_actions": [
                    "새 class schema major version 발행",
                    "runtime config와 TwoModelClassMap 동시 갱신",
                    "영향 모델 재-export",
                    "AIML-21 변환 동등성 재검증",
                ],
                "migration_status": "NOT_RUN",
                "conversion_equivalence_revalidation_status": "NOT_RUN",
            },
        }
    return schemas


def _runtime_asset_path(config: dict[str, Any]) -> Path:
    relative = Path(str(config["asset"]))
    _require(not relative.is_absolute(), f"runtime asset must be relative: {relative}")
    path = (ANDROID_ASSETS_DIR / relative).resolve()
    _require(path.is_relative_to(ANDROID_ASSETS_DIR.resolve()), f"runtime asset escapes assets directory: {relative}")
    _require(path.is_file(), f"runtime model asset missing: {relative}")
    return path


def _fp035_correction_candidate() -> dict[str, Any]:
    candidate = load_strict_json(FP035_CORRECTION_CANDIDATE_PATH)
    content = {key: value for key, value in candidate.items() if key != "candidate_content_sha256"}
    _require(candidate.get("candidate_content_sha256") == _object_sha256(content), "FP-035 correction candidate hash differs")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 candidate approval differs")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 candidate effectiveness differs")
    _require(candidate.get("correction", {}).get("normative_rule") == FP035_NORMATIVE_RULE, "FP-035 correction rule differs")
    _require(candidate.get("planned_effective_policy", {}).get("state_change_now") is False, "FP-035 candidate changed policy state")
    boundary = candidate.get("authorization_boundary", {})
    _require(boundary.get("owner_directive_captured") is True and boundary.get("new_product_question_required") is False, "FP-035 owner directive boundary differs")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate was treated as approval")
    return candidate


def _metadata(artifact_ids: list[str], title: str) -> dict[str, Any]:
    return {
        "document_id": f"WS-AIML-{title.upper().replace(' ', '-')}-20260721",
        "title": title,
        "document_version": VERSION,
        "as_of": AS_OF,
        "lifecycle_status": LIFECYCLE_STATUS,
        "approval_status": "NOT_APPROVED",
        "release_status": RELEASE_STATUS,
        "artifact_type_ids": artifact_ids,
    }


def _validate_inputs() -> dict[str, Any]:
    policy = load_strict_json(POLICY_PATH)
    approval = load_strict_json(APPROVAL_RECORD_PATH)
    baseline = load_strict_json(BASELINE_MANIFEST_PATH)
    catalog = load_strict_json(ARTIFACT_CATALOG_PATH)
    aligned = load_strict_json(ALIGNED_DECISION_REGISTER_PATH)
    _fp035_correction_candidate()
    approval_builder.validate_baseline_manifest(baseline, approval)
    alignment_builder.validate_alignment(aligned)

    payload = baseline.get("baseline_payload", {})
    target = payload.get("approval_target", {})
    _require(target.get("document_content_sha256") == POLICY_CONTENT_SHA256, "policy content fingerprint differs")
    _require(target.get("decision_binding_sha256") == DECISION_BINDING_SHA256, "decision fingerprint differs")
    _require(policy.get("document_content_sha256") == POLICY_CONTENT_SHA256, "policy payload fingerprint differs")
    _require(payload.get("approval_boundary", {}).get("release_status") == RELEASE_STATUS, "baseline release boundary differs")
    _require(payload.get("approval_boundary", {}).get("remaining_gates_are_waived") is False, "baseline waives gates")
    gates = policy.get("remaining_gates", [])
    _require(len(gates) == 5 and all(gate.get("status") == "NOT_RUN" for gate in gates), "five NOT_RUN gates not preserved")

    aiml = [item for item in catalog.get("artifact_types", []) if item.get("category") == "AIML"]
    _require(len(aiml) == 26, "AIML catalog count differs")
    by_id = {item["display_code"]: item for item in aiml}
    _require(set(by_id) == set(SCOPE_IDS), "AIML catalog IDs differ")
    expected_bundles = {
        "BND-AIML-DATA", "BND-AIML-MODEL", "BND-AIML-EVALUATION", "BND-AIML-OPS"
    }
    _require({item["recommended_bundle_id"] for item in aiml} == expected_bundles, "AIML bundle contract differs")
    _require(set(MATERIALIZED_IDS).isdisjoint(PLANNED_IDS), "Draft and Planned AIML scopes overlap")
    _require(set(MATERIALIZED_IDS) | set(PLANNED_IDS) == set(SCOPE_IDS), "AIML Draft and Planned scopes are incomplete")
    return {
        "policy": policy,
        "approval": approval,
        "baseline": baseline,
        "catalog": catalog,
        "aligned": aligned,
        "artifact_by_id": by_id,
    }


def _bullets(values: Iterable[str], empty: str = "없음") -> str:
    items = list(values)
    return "\n".join(f"- {item}" for item in items) if items else f"- {empty}"


def _header(title: str, bundle_id: str, draft_ids: list[str]) -> str:
    return f"""# {title}

상태: Draft
버전: {VERSION}
문서 묶음: {bundle_id}
Draft 산출물 유형: {", ".join(draft_ids)}
승인 상태: NOT_APPROVED
정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.0
정책 내용 지문: {POLICY_CONTENT_SHA256}
결정 지문: {DECISION_BINDING_SHA256}
실행 검증: NOT_RUN
출시 상태: {RELEASE_STATUS}
현행 제품: Android 사용자 앱과 별도 Android 관리자 앱
Web/PWA: LEGACY_REFERENCE_ONLY
민감 원본: Git 저장 금지

## 이 문서를 읽는 방법

Draft는 작성 가능한 계획·규칙·등록부 구조를 만들었다는 뜻입니다. 실제 학습이나 시험이 끝났다는 뜻이 아닙니다. Planned/NOT_RUN 항목은 실행 절차만 준비됐고 결과·합격·증거가 아직 없다는 뜻입니다.
"""


def _contract_section(item: dict[str, Any], detail: str, trace: dict[str, Any]) -> str:
    code = item["display_code"]
    state = "DRAFT" if code in MATERIALIZED_IDS else "PLANNED"
    execution = "NOT_RUN"
    upstream = [value.removeprefix("DLV-") for value in item.get("upstream_types", [])]
    downstream = [value.removeprefix("DLV-") for value in item.get("downstream_types", [])]
    planned_contract = ""
    if code in PLANNED_IDS:
        contract = PLANNED_EXECUTION_CONTRACTS[code]
        planned_contract = f"""
### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | {contract['when']} |
| 실행 책임 | {contract['executor']} |
| 필수 선행 | {_bullets(contract['prerequisites']).replace(chr(10), '<br>')} |
| 남겨야 할 증거 | {_bullets(contract['evidence']).replace(chr(10), '<br>')} |
| 판정 규칙 | {contract['judgment']} |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.
"""
    return f"""
<a id="{code.lower()}"></a>
## {code} {item["title"]}

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | {state} |
| 실제 실행 | {execution} |
| 적용 | {item["default_applicability"]} — {item["activation_condition"]} |
| 작성 | {item["owner_role"]} |
| 검토 | {", ".join(item["reviewer_roles"])} |
| 승인 | {item["approver_role"]}, 아직 미승인 |
| 선행 | {", ".join(upstream) if upstream else "없음"} |
| 후행 | {", ".join(downstream) if downstream else "없음"} |

목적: {item["purpose"]}

정책 추적: {", ".join(trace["policy_bundle_refs"]) if trace["policy_bundle_refs"] else "직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속"}

공통정책 추적: {", ".join(trace["common_policy_refs"])}

정렬 결정 추적: {", ".join(trace["direct_aligned_decision_refs"]) if trace["direct_aligned_decision_refs"] else "직접 결정 없음 — 상위 정책·선행 산출물로 추적"}

미완료 gate 추적: {", ".join(trace["remaining_gate_refs"]) if trace["remaining_gate_refs"] else "직접 gate 없음"}

OPEN 이슈 추적: {", ".join(trace["source_issue_refs"]) if trace["source_issue_refs"] else "직접 OPEN 이슈 없음"}

반드시 다룰 내용:

{_bullets(item["required_contents"])}

필요 입력:

{_bullets(item["required_inputs"])}

{detail.strip()}

{planned_contract.strip()}

완료·승인 기준:

{_bullets(item["completion_criteria"])}

갱신 조건:

{_bullets(item["update_triggers"])}

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.
"""


DETAILS = {
    "AIML-01": """
### 확정된 데이터 생명주기

- 별도 동의한 활성 보행에서 압축 원본 영상·음성·정확 위치·센서·탐지·경로·신고·성능 자료를 수집한다. 얼굴·번호판·주변 목소리를 자동으로 가린 원본으로 바꾸지 않는다.
- 서버 수신이 확인된 휴대전화 사본은 24시간 안에 삭제하고, 미전송 휴대전화 원본은 30일을 넘기지 않는다.
- 서버 수신·검역 원본은 14일, 일반·자동신고 원본은 180일, 승인 학습자료·라벨·고정 검증자료는 승인 뒤 3년, 운영 백업은 35일 보관한다.
- 전체 삭제 요청은 휴대전화 24시간, 서버 원본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일 안에 처리한다.
- 원본·동의서·정확 위치는 Git에 넣지 않는다. Git에는 통제 저장소 ID, 파일 지문, 권한, 보존기간, 처리상태만 둔다.

서버 주 원본 300 GiB 기준으로 70% 관리자 경고, 85% 신규 현장시험 참여자 추가 중단, 95% 만료자료 정리 후 새 원본수집 보류, 100% 새 학습자료·자동신고 후보 생성을 보류한다. 기존 암호화 자료와 실시간 탐지·길안내는 유지한다. FP-035는 일반 활동원본을 보행 중 전송하지 않고, 정지 뒤 이동통신망 명시 선택 시에만 이동통신망을 허용하며 미선택 시 Wi-Fi만 허용하는 것으로 정규화 지시를 포착했다. 원본수집 승인 자체는 바뀌지 않으며 새 산출물 묶음 승인 전 관련 시험은 `NOT_RUN`이다.
""",
    "AIML-02": """
### 현재 데이터셋 카드 결론

현 저장소에는 여러 과거 후보 manifest가 있으나, 승인 학습 데이터셋의 단일 ID·전체 파일 지문·sequence 안전 split·권리 검토를 함께 충족한 정본은 확인되지 않았다. 모델 후보가 가리키는 materialized_manifest.csv도 현재 작업공간에 없다. 따라서 이 카드는 후보 데이터의 존재와 결손만 설명하며 학습 재현성이나 제품 적합성을 주장하지 않는다.

허용 용도는 로컬 조사·정제·재검증 준비이고, 금지 용도는 출시 성능 주장, 독립 시험 대체, 권리 미확인 원본 공유, Web/PWA 결과의 Android 근거 전용이다.

| 카드 항목 | 현재 확인값 |
|---|---|
| 임시 ID | DS-CANDIDATE-FROM-MODEL-REGISTRY |
| 연결 manifest | datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/materialized_manifest.csv |
| manifest 존재 | 아니오 |
| 선언된 manifest 지문 | 5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6, 현재 파일이 없어 재검증 불가 |
| 후보 class 수 | 13 |
| 파일 수·지역·기간·분포 | 확인 불가 |
| 권리·동의 | NOT_VERIFIED |
| sequence-safe split | NOT_RUN |
| 품질·라벨·누수 검사 | NOT_RUN |
| 승인·학습 재현·출시 사용 | 불가 |
""",
    "AIML-03": """
### 출처·권리 확인 방법

각 출처는 제공자·원 URL·취득일·원본 버전·라이선스 원문 지문·학습/변형/재배포 허용범위·고지 의무·동의 근거·철회 영향을 한 행으로 기록한다. 현재 후보 행은 경로와 지문만 확인했으며 권리와 개인정보 검토는 NOT_VERIFIED다. 원본이나 동의서 대신 통제 저장소 참조만 기록한다.

정본 등록부: registers/data-source-register.json
""",
    "AIML-04": """
### 현재 Android runtime class-schema 계약

권위 입력은 `two_model_runtime.json`과 `TwoModelClassMap.kt`다. 두 입력의 모델 exact set과 class 순서가 다르면 builder가 실패한다. 현재 계약은 `unified_walksafe` 13개, `custom_tactile` 3개, `coco_general` 80개이며 전체 순서·qualified ID는 `registers/dataset-register.json`에 기록한다.

| runtime model ID | class 수 | numeric ID namespace |
|---|---:|---|
| unified_walksafe | 13 | walksafe.unified_walksafe.class_id |
| custom_tactile | 3 | walksafe.custom_tactile.class_id |
| coco_general | 80 | coco.coco_general.class_id |

numeric class ID는 모델별 namespace 안에서만 의미가 있다. 같은 canonical name이라도 서로 다른 모델의 numeric ID를 같다고 해석하지 않는다. canonical name은 runtime label을 trim하고 ASCII lowercase로 바꾼 뒤 영숫자가 아닌 연속 문자를 underscore 하나로 바꾸고 양끝 underscore를 제거한다. runtime label과 canonical name을 모두 보존한다.

class 추가·삭제·순서 변경은 모두 breaking major migration이다. 새 schema major version, runtime config와 Kotlin map의 동시 갱신, 영향 모델 재-export, AIML-21 변환 동등성 재검증이 필요하다. 현재 migration·재-export·동등성 검증은 `NOT_RUN`이며 AIML-05~13·16·17·21·23 완료를 주장하지 않는다.

이미지는 불변 sample_id, source_id, capture_group_id, content_sha256, width, height, captured_at 범주값, consent_record_id, storage_object_id를 가진다. 라벨은 sample_id, model-specific qualified class ID, normalized bbox 좌표, occlusion, truncation, reviewer_state, annotation_version을 가진다. 정확 위치와 사람 식별 가능 원본값은 Git용 schema에 직접 넣지 않고 통제 저장소 ID로 참조한다.

bbox는 x중심·y중심·너비·높이를 이미지 크기 0~1 범위로 정규화한다. 알 수 없는 class, 음수 크기, 이미지 밖 좌표, 중복 sample_id는 validator가 거부한다.
""",
    "AIML-05": """
### 실행 대기

완전성·손상·형식·중복·class/source 분포·개인정보·권리 검사를 아직 정식 데이터셋에 실행하지 않았다. 대상 dataset ID와 전체 content manifest가 고정된 뒤 동일 도구 버전으로 실행하고, 결과는 append-only evidence로 등록한다. 현재 결과·합격 판정·결함 수는 없다.
""",
    "AIML-06": """
### keep·fix·hold·drop 규칙

- keep: 출처·동의·형식·class·bbox가 모두 확인된 샘플
- fix: 원본 권리는 유효하고 라벨 또는 메타데이터의 수정 범위가 명확한 샘플
- hold: 작은 객체 기준, 권리, 동의, sequence 그룹, 개인정보 처리가 미결정인 샘플
- drop: 손상 파일, 허용되지 않은 출처, 철회 대상, 복구 불가능한 class/bbox 오류

결정은 sample_id와 사유 코드로 남기며 원본 파일을 조용히 덮어쓰지 않는다. blur·노출·해상도 숫자는 데이터 품질 실행 전 임의로 만들지 않고 protocol revision에서 정한다.
""",
    "AIML-07": """
### 라벨링 지침

13개 후보 class의 이름과 순서는 runtime 후보 설정에 결속한다. 보이는 물체의 실제 경계만 bbox로 표시하며, 가림·화면 잘림·작은 객체·여러 객체는 각각 상태를 기록한다. 같은 종류라는 이유로 서로 다른 객체를 합치지 않는다. 정상 점자블록을 파손으로 바꾸거나 애매한 장애물을 임의 class로 확정하지 않고 hold와 질문 기록을 사용한다.

신규 작업 전 positive/negative 경계와 class별 그림 예시를 개인정보 없는 승인 예시 세트로 확정해야 한다. 과거 relabel 자료는 후보 참고이며 새 기준의 품질검사를 통과하기 전 승격하지 않는다.

- 사람·자전거·자동차·오토바이·버스·화물차는 실제로 보이는 개체별로 각각 표시하고, 사진 밖 부분을 상상해 박스를 넓히지 않는다.
- 신호등은 기구 전체를 표시하되 색상 상태는 이 13-class label에 덧붙이지 않는다.
- 정상 점자블록과 파손 점자블록은 외관 근거가 분명한 경우에만 구분한다. 마모·그림자·오염만으로 파손을 확정하지 않는다.
- 횡단보도는 보이는 도색 범위를 기준으로 하며 도로 전체를 박스로 잡지 않는다.
- curb_step과 uneven_sidewalk는 의미가 겹치면 하나를 임의 선택하지 않고 hold한다. 승인 예시에서 물리적 경계와 표면 불균일의 구분을 먼저 고정한다.
- e_scooter_obstruction은 킥보드 존재가 아니라 보행 통로를 막는 상태가 보이는 경우를 후보로 한다. 통행 방해 여부가 사진 한 장으로 불명확하면 hold한다.
- 가려짐과 화면 잘림은 별도 flag로 남기며 보이는 부분이 너무 적어 class를 알 수 없으면 label하지 않고 질문 대기열에 넣는다.
""",
    "AIML-08": """
### 실행 대기

gold set, 이중 라벨 표본, 추출 seed, class·bbox 오류 기준과 합격선을 먼저 동결한 뒤 검사한다. 현재 정식 표본 추출·이중 검수·일치도 계산·재작업 결과는 없다. 과거 검토 파일의 존재를 이번 기준선의 PASS로 바꾸지 않는다.
""",
    "AIML-09": """
### 실행 대기

train/validation/test는 파일이 아니라 capture_group·연속 영상·장소·source 단위로 먼저 묶은 뒤 그룹 전체를 한 split에 둔다. dataset content manifest가 없으므로 현재 split 비율·seed·파일 hash·분포 결과는 확정하지 않았다. 독립 test를 학습·threshold 선택에 다시 사용하지 않는다.
""",
    "AIML-10": """
### 실행 대기

동일 content hash, 유사 이미지, 같은 capture sequence, 파생 crop, source 중복, 학습 데이터와 독립 test의 교차를 검사한다. 현재 정식 split이 없어 누수 0건 또는 합격을 주장할 수 없다. 발견 건은 원본 그룹 단위 재분할과 새 dataset version으로 처리한다.
""",
    "AIML-11": """
### 재현 계약

정식 학습 실행은 source commit, dataset ID·manifest hash, schema version, split hash, Python·CUDA·framework lock, 모델 초기 weight hash, 전체 설정, seed, 명령, 시작·종료시각, 실행환경 ID를 함께 기록한다. 현재 model/train_yolo.py와 관련 스크립트는 후보이며 승인 dataset과 결속해 다시 실행하기 전 재현 완료 근거가 아니다.
""",
    "AIML-12": """
### 실험 기록 규칙

실험은 EXP-YYYYMMDD-NNN ID로 추가하고 목적·가설·부모모델·dataset/split/config/code hash·환경·metric·artifact·결론·실패 원인을 기록한다. 실패한 실행도 삭제하지 않는다. 정식 실험 행은 현재 0개이며 후보 과거 기록은 별도 candidate_history에만 둔다.

정본 등록부: registers/experiment-register.json
""",
    "AIML-13": """
### 실행 대기

승인된 독립 test와 같은 protocol로 현재 기준모델, 새 후보, 안전한 단순 대안을 비교한다. 평가 결과를 본 뒤 metric이나 threshold를 바꾸지 않는다. 현재 비교실험 실행 0건이며 승자나 개선율은 없다.
""",
    "AIML-14": """
### 현재 runtime 3-model 등록 계약

정본 등록부는 Android runtime exact set인 `unified_walksafe`, `custom_tactile`, `coco_general` 세 모델만 등록한다. 각 행은 실제 asset path·SHA-256·byte size, 확인 가능한 input/output 계약, class schema namespace·순서, primary/fallback 역할을 runtime config와 Kotlin class map에 결속한다.

| runtime model ID | 역할 | 현재 provenance |
|---|---|---|
| unified_walksafe | primary | candidate PT와 Android TFLite 지문 결속, 미승인 |
| custom_tactile | legacy_two_model tactile fallback component | UNKNOWN_PROVENANCE |
| coco_general | legacy_two_model general fallback component | UNKNOWN_PROVENANCE |

fallback TFLite의 `source_model` 값이 Android asset 자체를 가리키므로 이를 원 학습 artifact나 변환 provenance로 만들지 않는다. 확인할 수 없는 source·변환 이력은 `UNKNOWN_PROVENANCE`로 유지한다.

candidate → evaluated → approved → deployed → retired 순서만 허용한다. 현재 세 모델은 모두 candidate이며 `deployment_eligible=false`, 평가·변환 동등성·Android 기기 성능은 `NOT_RUN`, 승인은 `NOT_APPROVED`, 출시는 `NOT_ELIGIBLE`다. 개발 runtime active 표시는 제품 승인이나 출시 배포를 뜻하지 않으며 AIML-15·16·17·21·23 완료를 주장하지 않는다.
""",
    "AIML-15": """
### 후보 모델 카드

현재 후보는 Android에서 13종 물체 후보를 만들기 위한 YOLO 계열 모델이다. 거리·위험 단계·사용자 행동·안전 여부·자동신고를 단독 결정하지 않는다. 독립 test, content hash 기반 dataset, sequence-safe split, 낮은 curb_step·uneven_sidewalk recall 보완, 변환 동등성, 지원기기 성능이 미완료이므로 안전 보장·출시 적합·지원 class 확정을 주장하지 않는다.

| 카드 항목 | 현재 후보 사실 |
|---|---|
| 모델 ID | walksafe-13cls-yolo26n-img768-epoch270-20260708 |
| 구조 | YOLO26n 계열 객체 탐지 후보 |
| source 파일 | model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt |
| source SHA-256 | a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669 |
| Android 파일 | apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite |
| Android SHA-256 | 92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19 |
| 입력 | float32, 1×768×768×3 후보 |
| 출력 | float32, 1×300×6 후보 |
| class | AIML-04의 13개 후보 순서 |
| 사용 목적 | FP-019 물체 후보와 관측 근거 생성 |
| 금지 목적 | 거리·위험 단계·행동·안전함·자동신고의 단독 결정, 사람 식별 |
| 현재 단계 | CANDIDATE, deployment_eligible=false |

기존 registry에는 precision 0.62087, recall 0.56716, mAP50 0.55403, mAP50-95 0.41651이 적혀 있다. 이 값은 연결 dataset manifest와 results.csv가 현재 없고 독립 test도 아니므로 이번 기준선의 정식 평가값으로 사용하지 않는다. 알려진 blocker는 독립 test 없음, dataset content hash 없음, 촬영 sequence split 누수, curb_step·uneven_sidewalk recall 부족이다.

예상 사용자는 보행 중 위험 정보를 음성·진동으로 받는 사용자이지만, 모델이 놓칠 수 있으므로 모델만 믿고 걸어도 된다고 설명하지 않는다. 조명·날씨·흔들림·가림·거리·기기 차이와 학습자료 분포 밖 장면은 미검증 제한이다.
""",
    "AIML-16": """
### 파일·지문 원칙

등록부는 실제 존재하는 PT·TFLite와 runtime 설정의 SHA-256을 다시 계산한다. 레지스트리의 선언값과 다르면 생성에 실패한다. 파일 존재와 지문 일치는 동일 파일임을 보일 뿐 학습 재현·정확도·TFLite 동등성·Android 적합성을 증명하지 않는다.

정본 등록부: registers/model-register.json
""",
    "AIML-17": """
### 사전 동결 평가 프로토콜

평가 전에 모델 hash, dataset·split hash, 13개 class 순서, 입력 크기, 전처리/NMS, metric 정의, IoU grid, confidence grid, 오류 표본 규칙, 강건성 조건, TFLite 비교 허용오차, Android 기기 목록을 revision으로 동결한다. 독립 test는 학습·모델 선택·threshold 선택에 사용하지 않는다.

결과 열람 뒤 protocol을 바꾸면 기존 결과를 승인에 쓰지 않고 새 revision과 새 실행 ID로 다시 평가한다. 현재 dataset/split과 허용오차 숫자가 미확정이므로 실행은 NOT_RUN이다.

실행 순서는 다음과 같다.

1. dataset·split·모델·config·코드·환경 파일 지문을 확인하고 하나라도 다르면 시작하지 않는다.
2. confidence와 IoU 후보 grid, class별 안전 우선순위, 합격기준을 결과 열람 전에 서명한다.
3. 고정 test 전체를 한 번 실행하고 원시 prediction과 계산 로그를 append-only 저장소에 남긴다.
4. precision은 모델이 맞다고 한 후보 중 맞은 비율, recall은 실제 정답 중 찾은 비율로 계산한다. AP와 mAP 계산 도구·버전도 기록한다.
5. 전체 평균만 보지 않고 13개 class별 표본 수·precision·recall·AP와 FP·FN을 공개한다.
6. source PT와 Android TFLite를 같은 corpus로 비교하고, 지원 Android 기기에서 앱 전체 지연·발열을 별도 측정한다.
7. 결과가 기준을 못 넘으면 FAIL 또는 BLOCKED로 기록하고 threshold를 몰래 바꾸지 않는다.
""",
    "AIML-18": """
### 실행 대기

전체와 13개 class별 precision, recall, AP, mAP, 표본 수와 confidence interval을 계산할 정식 실행은 없다. 과거 registry metric은 training history 후보이고 이번 독립 평가 결과에 포함하지 않는다.
""",
    "AIML-19": """
### 실행 대기

독립 평가의 FP·FN을 class, 거리, 조명, 날씨, 흔들림, 가림, 기기 조건으로 표본화하고 사용자·안전 영향을 함께 분류한다. 원본 사례는 통제 저장소에 두고 Git에는 비식별 thumbnail 또는 참조 ID만 둔다. 현재 정식 오류 표본과 원인 판정은 없다.
""",
    "AIML-20": """
### 실행 대기

주간·야간·역광·저조도·비·흔들림·가림·다양한 Android 기기와 보행 조건에서 baseline 대비 저하를 측정한다. 사람 특성을 추정하거나 민감집단 label을 새로 만들지 않으며, 필요한 집단 평가는 동의·최소수집 계획을 별도 승인한다. 현재 결과·완화 판정·잔여제한 승인은 없다.
""",
    "AIML-21": """
### 실행 대기

같은 입력 corpus를 source PT와 배포 TFLite에 넣어 class, score, box, NMS 후 결과와 metric 차이를 비교한다. 두 파일의 hash는 등록됐지만 동일 입력 비교와 허용오차 승인은 실행되지 않았다. 따라서 변환 동등성은 NOT_RUN이고 WS-10도 완료되지 않았다.
""",
    "AIML-22": """
### 실행 대기

지원 후보 Android 휴대전화에서 카메라·거리·음성 기능을 함께 켜고 warmup, 반복 수, 전원·온도 조건을 고정해 latency percentile, FPS, memory, battery, thermal throttling을 측정한다. 브라우저는 Web/PWA가 별도 재승인될 때만 추가한다. 현재 정식 기기 결과는 없다.
""",
    "AIML-23": """
### 실행 대기

confidence·IoU·위험 안내 threshold는 독립 평가의 precision/recall curve와 class별 FP·FN 안전 비용을 함께 보고 선택한다. 현재 Android 설정의 숫자는 개발 후보이며 승인 threshold가 아니다. 평가 전 후보 grid를 고정하고 test 결과를 본 뒤 임의로 최적화하지 않는다.
""",
    "AIML-24": """
### 배포·롤백 절차

승격 전 모델·config·class 순서·앱 build를 하나의 release generation으로 묶고, registry evaluated/approved 상태, AIML-18·20·21·22·23 결과, WS-08~10, 보안·개인정보 gate를 확인한다. 제한된 canary에서 오류·지연·발열·신고 이상을 관찰하고 trigger 충족 시 신규 세션을 막는다. 현재 DB와 호환되고 새 자료가 손실되지 않음을 시험으로 입증한 구성요소만 이전의 승인된 모델·config·앱 묶음으로 되돌리며, 그렇지 않은 구성요소는 안전정지한 채 수정판을 준비한다.

현재 승인된 이전 모델과 정식 release generation이 없으므로 실제 rollback 실행은 NOT_RUN이다.
""",
    "AIML-25": """
### 개인정보 최소 운영 관측

모델 version별 탐지량, class별 confidence 분포, 입력 품질 실패율, 지연·중지·발열, 관리자 검수 결과처럼 필요한 최소 proxy만 집계한다. 원본 영상을 기본 metric payload에 넣지 않는다. baseline과 alert 숫자는 정식 평가·현장시험 뒤 정하고, drift 경고만으로 자동 재학습·자동 배포하지 않는다.

현재 운영 baseline, drift 실행, alert, triage 결과는 없다.
""",
    "AIML-26": """
### 재학습 승인 흐름

재현 가능한 성능 저하, 새 환경·기기·class, 중대한 오탐·미탐, 데이터 분포 변화, 보안·런타임 변경이 trigger 후보이다. 제품책임자가 재학습 착수를 승인한 뒤 새 data version과 sequence-safe split을 고정하고, 비교·안전·동등성·기기 평가와 배포·rollback 준비를 모두 다시 통과시킨다.

긴급 상황에서도 미검증 모델을 자동 배포하지 않는다. 이전 승인본 복구는 현재 DB 호환과 새 자료 무손실을 시험으로 입증한 구성요소에만 허용한다. 나머지는 모델 기능을 안전정지하고 수정판을 준비한 뒤 정식 절차를 따른다.
""",
}


def _document(path: Path, inputs: dict[str, Any], trace_by_code: dict[str, dict[str, Any]]) -> str:
    spec = DOCUMENT_SCOPE[path]
    result = _header(spec["title"], spec["bundle_id"], spec["draft_ids"])
    planned = [code for code in spec["all_ids"] if code in PLANNED_IDS]
    result += f"""
## 현재 묶음 상태

- Draft로 작성된 유형: {", ".join(spec["draft_ids"])}
- 실행 전 Planned/NOT_RUN 유형: {", ".join(planned) if planned else "없음"}
- 실제 학습·평가·운영 실행 수: 0
- 실제 PASS 수: 0
- 승인된 출시 모델 수: 0
- 남은 정책 gate: 5개, 모두 NOT_RUN·미면제
- FP-035 이동통신망 조건: `{FP035_ISSUE_ID}` 정규화 지시 포착·묶음 승인 대기. 정정 후보 `NOT_APPROVED/NOT_EFFECTIVE`. 보행 중 미전송, 정지 뒤 명시 선택 시 이동통신망 허용, 미선택 시 Wi-Fi만 허용. 관련 시험 `NOT_RUN`

## 쉬운 용어

- dataset: 모델이 학습하거나 평가할 자료 묶음
- label: 사진 속 물체의 종류와 위치를 표시한 정답
- split: 학습용·조정용·독립 시험용 자료를 서로 섞이지 않게 나눈 것
- hash 또는 파일 지문: 파일이 같은지 확인하는 긴 값
- threshold: 모델 점수가 어느 정도일 때 후보로 인정할지 정한 기준
- drift: 운영 중 입력이나 결과의 분포가 기준 시점과 달라지는 현상
- candidate: 조사·재검증 대상이며 승인·배포가 확정되지 않은 후보
"""
    for code in spec["all_ids"]:
        result += _contract_section(
            inputs["artifact_by_id"][code],
            DETAILS[code],
            trace_by_code[code],
        )
    result += """
## 공통 승인 경계

이 문서의 구조검사 통과는 데이터 품질, 학습 재현, 모델 정확도, Android 성능, 개인정보 적합성 또는 출시 가능을 뜻하지 않는다. 승인된 데이터셋·모델·평가 증거가 생기면 같은 ID와 hash로 연결해 새 revision을 만들고 사람 검토와 제품책임자 승인을 받는다.
"""
    return result


def _candidate_file(relative: str, purpose: str) -> dict[str, Any]:
    path = REPO_ROOT / relative
    return {
        "path": relative,
        "purpose": purpose,
        "exists": path.is_file(),
        "sha256": _sha256_file(path) if path.is_file() else None,
        "byte_length": path.stat().st_size if path.is_file() else None,
        "classification": "CANDIDATE_REVALIDATION_REQUIRED",
        "formal_evidence": False,
    }


def _data_source_register() -> dict[str, Any]:
    entries = []
    for source_id, path, purpose in DATA_SOURCE_CANDIDATES:
        candidate = _candidate_file(path, purpose)
        entries.append(
            {
                "source_id": source_id,
                **candidate,
                "provider": None,
                "source_url": None,
                "acquired_at": None,
                "source_version": None,
                "license_or_consent_status": "NOT_VERIFIED",
                "training_permission_status": "NOT_VERIFIED",
                "redistribution_status": "NOT_VERIFIED",
                "privacy_review_status": "NOT_RUN",
                "promotion_eligible": False,
                "blockers": ["RIGHTS_EVIDENCE_MISSING", "PRIVACY_REVIEW_NOT_RUN"],
            }
        )
    return {
        "schema_version": "walksafe.aiml-data-source-register.v1",
        "metadata": _metadata(["AIML-03"], "data-source-register"),
        "storage_boundary": {
            "raw_personal_data_in_git_allowed": False,
            "git_content": "최소 metadata·통제 저장소 ID·hash·권한·보존정책만 허용",
        },
        "entries": entries,
        "summary": {
            "candidate_count": len(entries),
            "rights_verified_count": 0,
            "privacy_reviewed_count": 0,
            "promotion_eligible_count": 0,
        },
    }


def _dataset_register(
    runtime: dict[str, Any],
    source_registry: dict[str, Any],
    class_schemas: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    model = source_registry["models"][0]
    dataset = model["dataset"]
    manifest_path = str(dataset["manifest_path"])
    manifest = REPO_ROOT / manifest_path
    classes = runtime["models"]["unified_walksafe"]["classes"]
    return {
        "schema_version": "walksafe.aiml-dataset-register.v1",
        "metadata": _metadata(["AIML-02", "AIML-04"], "dataset-register"),
        "internal_candidate_completion_scope": ["AIML-04"],
        "non_claimed_completion_ids": [
            "AIML-05", "AIML-06", "AIML-07", "AIML-08", "AIML-09", "AIML-10",
            "AIML-11", "AIML-12", "AIML-13", "AIML-16", "AIML-17", "AIML-21", "AIML-23",
        ],
        "runtime_class_schema_exact_model_ids": list(RUNTIME_MODEL_IDS),
        "runtime_class_schemas": [class_schemas[model_id] for model_id in RUNTIME_MODEL_IDS],
        "schema_draft": {
            "schema_id": "WS-DATASET-SCHEMA-DRAFT",
            "version": "0.1.0",
            "status": "DRAFT_NOT_APPROVED",
            "class_order_candidate": classes,
            "sample_fields": [
                "sample_id", "source_id", "capture_group_id", "content_sha256",
                "width", "height", "consent_record_id", "storage_object_id",
            ],
            "annotation_fields": [
                "sample_id", "class_id", "bbox_xywh_normalized", "occlusion",
                "truncation", "reviewer_state", "annotation_version",
            ],
            "raw_personal_values_in_git": False,
        },
        "datasets": [
            {
                "dataset_id": "DS-CANDIDATE-FROM-MODEL-REGISTRY",
                "version": None,
                "status": "CANDIDATE_REVALIDATION_REQUIRED",
                "manifest_path": manifest_path,
                "manifest_exists": manifest.is_file(),
                "declared_manifest_sha256": dataset.get("manifest_sha256"),
                "verified_manifest_sha256": _sha256_file(manifest) if manifest.is_file() else None,
                "content_hash_status": "NOT_VERIFIED",
                "split_status": "NOT_RUN",
                "leakage_check_status": "NOT_RUN",
                "data_quality_status": "NOT_RUN",
                "label_quality_status": "NOT_RUN",
                "rights_status": "NOT_VERIFIED",
                "class_count": dataset.get("class_count"),
                "class_order_candidate": classes,
                "training_or_release_eligible": False,
                "blockers": [
                    "DATASET_MANIFEST_MISSING" if not manifest.is_file() else "CONTENT_HASHES_NOT_VERIFIED",
                    "SEQUENCE_SAFE_SPLIT_NOT_VERIFIED",
                    "RIGHTS_AND_PRIVACY_REVIEW_NOT_RUN",
                ],
            }
        ],
        "formal_dataset_count": 0,
        "approved_dataset_count": 0,
    }


def _model_register(
    runtime: dict[str, Any],
    source_registry: dict[str, Any],
    deployment: dict[str, Any],
    class_schemas: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    registered = source_registry["models"][0]
    artifact_path = REPO_ROOT / registered["artifact"]["path"]
    export = registered["exports"]["android_tflite"]
    export_path = REPO_ROOT / export["path"]
    _require(artifact_path.is_file(), "registered candidate PT is missing")
    _require(export_path.is_file(), "registered candidate TFLite is missing")
    _require(_sha256_file(artifact_path) == registered["artifact"]["sha256"], "candidate PT hash differs")
    _require(_sha256_file(export_path) == export["sha256"], "candidate TFLite hash differs")
    role_by_model = {
        "unified_walksafe": "PRIMARY",
        "custom_tactile": "LEGACY_TWO_MODEL_TACTILE_FALLBACK_COMPONENT",
        "coco_general": "LEGACY_TWO_MODEL_GENERAL_FALLBACK_COMPONENT",
    }
    models = []
    for runtime_model_id in RUNTIME_MODEL_IDS:
        config_model = runtime["models"][runtime_model_id]
        android_path = _runtime_asset_path(config_model)
        android_sha256 = _sha256_file(android_path)
        _require(android_sha256 == config_model["artifact_sha256"], f"runtime TFLite hash differs: {runtime_model_id}")
        if runtime_model_id == "unified_walksafe":
            _require(android_path == export_path.resolve(), "unified runtime/export path differs")
            source_artifact = {
                "provenance_status": "CANDIDATE_SOURCE_ARTIFACT_BOUND_NOT_APPROVED",
                "path": _rel(artifact_path),
                "sha256": _sha256_file(artifact_path),
                "byte_length": artifact_path.stat().st_size,
                "format": registered["artifact"]["format"],
            }
            input_contract = {
                "status": "DECLARED_IN_RUNTIME_EXPORT",
                "shape": export["input_shape"],
                "dtype": config_model["export"]["input_dtype"],
                "image_side_pixels": config_model["input_size"],
            }
            output_contract = {
                "status": "DECLARED_IN_RUNTIME_EXPORT",
                "shape": export["output_shape"],
                "dtype": config_model["export"]["output_dtype"],
                "class_id_namespace": RUNTIME_CLASS_NAMESPACES[runtime_model_id],
            }
            blockers = registered["blockers"]
            metrics = registered.get("metrics", {})
            registry_model_id = registered["model_id"]
        else:
            source_artifact = {
                "provenance_status": "UNKNOWN_PROVENANCE",
                "path": None,
                "sha256": None,
                "byte_length": None,
                "format": None,
                "declared_runtime_source_reference": config_model.get("source_model"),
                "reason": "runtime source_model points to the Android asset itself, not an independently verified source artifact",
            }
            input_contract = {
                "status": "PARTIAL_INPUT_SIZE_ONLY",
                "shape": None,
                "dtype": None,
                "image_side_pixels": config_model["input_size"],
            }
            output_contract = {
                "status": "CLASS_ORDER_ONLY_TENSOR_CONTRACT_NOT_DECLARED",
                "shape": None,
                "dtype": None,
                "class_id_namespace": RUNTIME_CLASS_NAMESPACES[runtime_model_id],
            }
            blockers = [
                "UNKNOWN_PROVENANCE",
                "CONVERSION_EQUIVALENCE_NOT_RUN",
                "ANDROID_DEVICE_PERFORMANCE_NOT_RUN",
            ]
            metrics = {}
            registry_model_id = None
        models.append(
            {
                "model_id": runtime_model_id,
                "runtime_model_id": runtime_model_id,
                "candidate_registry_model_id": registry_model_id,
                "registry_stage": "CANDIDATE",
                "approval_status": "NOT_APPROVED",
                "source_artifact": source_artifact,
                "android_artifact": {
                    "path": _rel(android_path),
                    "asset_path": config_model["asset"],
                    "sha256": android_sha256,
                    "byte_length": android_path.stat().st_size,
                    "format": "TFLITE",
                },
                "input_contract": input_contract,
                "output_contract": output_contract,
                "class_schema": {
                    "schema_id": class_schemas[runtime_model_id]["schema_id"],
                    "class_id_namespace": class_schemas[runtime_model_id]["class_id_namespace"],
                    "class_count": class_schemas[runtime_model_id]["class_count"],
                    "class_order": class_schemas[runtime_model_id]["class_order"],
                    "class_order_sha256": class_schemas[runtime_model_id]["class_order_sha256"],
                },
                "runtime_role": role_by_model[runtime_model_id],
                "fallback_contract": (
                    {
                        "fallback_alias": runtime["fallback_model"],
                        "fallback_runtime_model_ids": ["custom_tactile", "coco_general"],
                    }
                    if runtime_model_id == "unified_walksafe"
                    else {
                        "member_of_fallback_alias": runtime["fallback_model"],
                        "fallback_for_runtime_model_id": "unified_walksafe",
                    }
                ),
                "runtime_config": {
                    "path": _rel(RUNTIME_CONFIG_PATH),
                    "sha256": _sha256_file(RUNTIME_CONFIG_PATH),
                    "thresholds_candidate_not_approved": config_model["thresholds"],
                },
                "deployment_candidate_state": (
                    deployment["targets"]["android"]["runtime_state"]
                    if runtime_model_id == "unified_walksafe"
                    else "DEVELOPMENT_FALLBACK_COMPONENT"
                ),
                "deployment_eligible": False,
                "evaluation_status": "NOT_RUN",
                "conversion_equivalence_status": "NOT_RUN",
                "android_device_performance_status": "NOT_RUN",
                "release_status": RELEASE_STATUS,
                "blockers": blockers,
                "candidate_metrics_not_formal_evaluation": metrics,
            }
        )
    return {
        "schema_version": "walksafe.aiml-model-register.v2",
        "metadata": _metadata(["AIML-14", "AIML-16"], "model-register"),
        "internal_candidate_completion_scope": ["AIML-14"],
        "non_claimed_completion_ids": ["AIML-15", "AIML-16", "AIML-17", "AIML-21", "AIML-23"],
        "runtime_model_exact_ids": list(RUNTIME_MODEL_IDS),
        "runtime_model_exact_set_sha256": _object_sha256(list(RUNTIME_MODEL_IDS)),
        "runtime_model_count": len(RUNTIME_MODEL_IDS),
        "runtime_config_binding": _source_binding(RUNTIME_CONFIG_PATH),
        "kotlin_class_map_binding": _source_binding(TWO_MODEL_CLASS_MAP_PATH),
        "primary_runtime_model_id": runtime["primary_model"],
        "fallback_alias": runtime["fallback_model"],
        "models": models,
        "unknown_provenance_model_ids": ["custom_tactile", "coco_general"],
        "approved_model_count": 0,
        "deployed_release_model_count": 0,
        "warning": "파일 존재·지문 일치는 평가·동등성·출시 승인을 뜻하지 않는다.",
    }


def _experiment_register() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.aiml-experiment-register.v1",
        "metadata": _metadata(["AIML-12"], "experiment-register"),
        "required_fields": [
            "experiment_id", "purpose", "hypothesis", "source_commit", "dataset_id",
            "dataset_manifest_sha256", "split_sha256", "config_sha256", "code_sha256",
            "seed", "environment_id", "started_at", "ended_at", "status",
            "metrics", "artifact_ids", "conclusion", "failure_reason",
        ],
        "formal_experiments": [],
        "formal_execution_count": 0,
        "candidate_training_controls": [
            _candidate_file(path, "학습·검증·변환 후보 코드")
            for path in MODEL_CODE_CANDIDATES
        ],
        "candidate_history": [
            {
                "source": _rel(MODEL_SOURCE_REGISTRY_PATH),
                "status": "CANDIDATE_METADATA_ONLY_REVALIDATION_REQUIRED",
                "formal_experiment_id": None,
                "reason": "dataset manifest와 results 파일이 현재 작업공간에 없어 정식 재현 기록으로 승격하지 않음",
            }
        ],
    }


def _evaluation_register(policy: dict[str, Any]) -> dict[str, Any]:
    planned = [
        {
            "artifact_type_id": code,
            "execution_contract": PLANNED_EXECUTION_CONTRACTS[code],
            "execution_status": "NOT_RUN",
            "result": None,
            "evidence_ids": [],
            "pass_claimed": False,
            "approval_status": "NOT_APPROVED",
        }
        for code in PLANNED_IDS
    ]
    return {
        "schema_version": "walksafe.aiml-evaluation-evidence-register.v1",
        "metadata": _metadata([], "evaluation-evidence-register"),
        "supports_planned_artifact_type_ids": PLANNED_IDS,
        "protocol_artifact_type_id": "AIML-17",
        "planned_executions": planned,
        "formal_execution_count": 0,
        "pass_count": 0,
        "remaining_gates": [
            {"id": gate["id"], "status": "NOT_RUN", "waived": False}
            for gate in policy["remaining_gates"]
        ],
        "fp035_open_issue": {
            "issue_id": FP035_ISSUE_ID,
            "status": FP035_NORMALIZATION_STATUS,
            "scope": "일반 활동원본의 보행·정지·허용 네트워크 분기",
            "owner_clarification_required": False,
            "normalized_policy": FP035_NORMALIZED_POLICY,
            "normative_rule": FP035_NORMATIVE_RULE,
            "correction_candidate_binding": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
            "correction_candidate_approval_status": "NOT_APPROVED",
            "correction_candidate_effective_status": "NOT_EFFECTIVE",
            "policy_effect_claimed": False,
            "related_test_status": "NOT_RUN",
            "raw_collection_policy_reopened": False,
        },
    }


def _operations_register(source_registry: dict[str, Any], deployment: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.aiml-model-operations-register.v1",
        "metadata": _metadata([], "model-operations-register"),
        "supports_draft_artifact_type_ids": ["AIML-24", "AIML-25", "AIML-26"],
        "candidate_binding": {
            "model_id": source_registry["models"][0]["model_id"],
            "development_runtime_state": deployment["targets"]["android"]["runtime_state"],
            "release_model": False,
        },
        "deployment_runs": [],
        "rollback_runs": [],
        "monitoring_baselines": [],
        "drift_events": [],
        "retraining_requests": [],
        "formal_operation_count": 0,
        "release_status": RELEASE_STATUS,
    }


def _source_bindings() -> dict[str, dict[str, str]]:
    return {
        "policy": _source_binding(POLICY_PATH),
        "policy_approval_record": _source_binding(APPROVAL_RECORD_PATH),
        "policy_baseline_manifest": _source_binding(BASELINE_MANIFEST_PATH),
        "artifact_catalog": _source_binding(ARTIFACT_CATALOG_PATH),
        "aligned_decision_register": _source_binding(ALIGNED_DECISION_REGISTER_PATH),
        "fp035_correction_candidate": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
        "control_trace_helper": _source_binding(control_builder.GENERATOR_PATH),
        "candidate_model_registry": _source_binding(MODEL_SOURCE_REGISTRY_PATH),
        "candidate_deployment": _source_binding(DEPLOYMENT_SOURCE_PATH),
        "candidate_android_runtime_config": _source_binding(RUNTIME_CONFIG_PATH),
        "candidate_android_kotlin_class_map": _source_binding(TWO_MODEL_CLASS_MAP_PATH),
        "generator": _source_binding(GENERATOR_PATH),
    }


def _artifact_trace(inputs: dict[str, Any]) -> list[dict[str, Any]]:
    common_refs = [
        "NPC-RAW-ORIGINAL-COLLECTION",
        "NPC-DATA-LIFECYCLE",
        "NPC-SERVER-STORAGE-CAPACITY",
    ]
    catalog_items = control_builder._select_and_validate_catalog(inputs["catalog"])
    approved_trace = control_builder._approved_input_trace_by_code(catalog_items)
    records = []
    for code in SCOPE_IDS:
        trace = approved_trace[code]
        direct_decisions = trace["decision_ids"]
        records.append(
            {
                "artifact_type_id": code,
                "materialization_state": "DRAFT" if code in MATERIALIZED_IDS else "PLANNED_NOT_RUN",
                "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
                "policy_bundle_refs": trace["feature_policy_ids"],
                "common_policy_refs": common_refs,
                "direct_aligned_decision_refs": direct_decisions,
                "direct_decision_link_status": (
                    "DIRECT_LINKS_INDEXED"
                    if direct_decisions
                    else "NO_DIRECT_DECISION_USE_POLICY_AND_UPSTREAM_TRACE"
                ),
                "remaining_gate_refs": trace["gate_ids"],
                "source_issue_refs": trace["open_issue_refs"],
                "verification_status": "NOT_RUN",
                "approval_status": "NOT_APPROVED",
            }
        )
    return records


def _build_outputs() -> dict[Path, bytes]:
    inputs = _validate_inputs()
    runtime = load_strict_json(RUNTIME_CONFIG_PATH)
    class_schemas = _runtime_class_schemas(runtime)
    source_registry = load_strict_json(MODEL_SOURCE_REGISTRY_PATH)
    deployment = load_strict_json(DEPLOYMENT_SOURCE_PATH)
    trace_records = _artifact_trace(inputs)
    trace_by_code = {record["artifact_type_id"]: record for record in trace_records}

    outputs: dict[Path, bytes] = {
        path: _md_bytes(_document(path, inputs, trace_by_code))
        for path in DOCUMENT_SCOPE
    }
    outputs.update(
        {
            DATA_SOURCE_REGISTER_PATH: _json_bytes(_data_source_register()),
            DATASET_REGISTER_PATH: _json_bytes(_dataset_register(runtime, source_registry, class_schemas)),
            MODEL_REGISTER_PATH: _json_bytes(_model_register(runtime, source_registry, deployment, class_schemas)),
            EXPERIMENT_REGISTER_PATH: _json_bytes(_experiment_register()),
            EVALUATION_REGISTER_PATH: _json_bytes(_evaluation_register(inputs["policy"])),
            OPERATIONS_REGISTER_PATH: _json_bytes(_operations_register(source_registry, deployment)),
        }
    )

    generated_files = [
        {
            "path": _rel(path),
            "sha256": _sha256_bytes(content),
            "byte_length": len(content),
            "artifact_type_ids": FILE_DRAFT_COVERAGE[path],
            **(
                {"supports_planned_artifact_type_ids": PLANNED_IDS}
                if path == EVALUATION_REGISTER_PATH
                else {}
            ),
        }
        for path, content in sorted(outputs.items(), key=lambda item: _rel(item[0]))
    ]
    manifest = {
        "schema_version": "walksafe.formal-aiml-draft-manifest.v1",
        "metadata": {
            **_metadata(MATERIALIZED_IDS, "WalkSafe AIML 정식 산출물 Draft manifest"),
            "manifest_id": "WS-FORMAL-AIML-DRAFT-20260721-001",
            "controlled_revision": 1,
            "generated_by": _rel(GENERATOR_PATH),
        },
        "scope_artifact_type_ids": SCOPE_IDS,
        "materialized_artifact_type_ids": MATERIALIZED_IDS,
        "planned_artifact_type_ids": PLANNED_IDS,
        "planned_execution_contracts": PLANNED_EXECUTION_CONTRACTS,
        "source_bindings": _source_bindings(),
        "source_binding_sha256": _object_sha256(_source_bindings()),
        "artifact_trace": trace_records,
        "generated_files": generated_files,
        "coverage": {
            "scope_count": len(SCOPE_IDS),
            "materialized_draft_count": len(MATERIALIZED_IDS),
            "planned_not_run_count": len(PLANNED_IDS),
            "bundle_count": 4,
            "missing_ids": [],
            "duplicate_ids": [],
        },
        "candidate_boundary": {
            "current_model_and_code_are_candidates": True,
            "candidate_revalidation_required": True,
            "existing_candidate_metrics_are_formal_evaluation": False,
            "approved_dataset_count": 0,
            "approved_model_count": 0,
            "formal_training_run_count": 0,
            "formal_evaluation_run_count": 0,
            "formal_monitoring_run_count": 0,
        },
        "sensitive_data_boundary": {
            "raw_video_audio_location_in_git_allowed": False,
            "controlled_storage_reference_required": True,
            "raw_collection_policy_status": "APPROVED",
            "fp035_network_issue_id": FP035_ISSUE_ID,
            "fp035_network_issue_status": FP035_NORMALIZATION_STATUS,
            "fp035_normalized_policy": FP035_NORMALIZED_POLICY,
            "fp035_related_test_status": "NOT_RUN",
            "fp035_correction_candidate_binding": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
            "fp035_correction_candidate_approval_status": "NOT_APPROVED",
            "fp035_correction_candidate_effective_status": "NOT_EFFECTIVE",
            "fp035_policy_effect_claimed": False,
        },
        "remaining_gates": [
            {"id": gate["id"], "status": "NOT_RUN", "waived": False}
            for gate in inputs["policy"]["remaining_gates"]
        ],
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "formal_deliverable_lifecycle_status": LIFECYCLE_STATUS,
            "formal_deliverables_approved": False,
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
    }
    manifest["manifest_content_sha256"] = _object_sha256(manifest)
    outputs[MANIFEST_PATH] = _json_bytes(manifest)
    return outputs


def _validate_generated_outputs(outputs: dict[Path, bytes]) -> None:
    _require(
        set(PLANNED_EXECUTION_CONTRACTS) == set(PLANNED_IDS),
        "planned AIML execution contracts must cover Planned IDs exactly",
    )
    expected_paths = set(DOCUMENT_SCOPE) | {
        DATA_SOURCE_REGISTER_PATH,
        DATASET_REGISTER_PATH,
        MODEL_REGISTER_PATH,
        EXPERIMENT_REGISTER_PATH,
        EVALUATION_REGISTER_PATH,
        OPERATIONS_REGISTER_PATH,
        MANIFEST_PATH,
    }
    _require(set(outputs) == expected_paths, "generated AIML path set differs")
    manifest = json.loads(outputs[MANIFEST_PATH])
    materialized = set(manifest["materialized_artifact_type_ids"])
    planned = set(manifest["planned_artifact_type_ids"])
    scope = set(manifest["scope_artifact_type_ids"])
    _require(materialized == set(MATERIALIZED_IDS), "manifest materialized scope differs")
    _require(planned == set(PLANNED_IDS), "manifest planned scope differs")
    _require(materialized.isdisjoint(planned), "manifest Draft and Planned scopes overlap")
    _require(materialized | planned == scope == set(SCOPE_IDS), "manifest AIML scope union differs")
    _require(manifest["metadata"]["artifact_type_ids"] == MATERIALIZED_IDS, "manifest metadata must declare Draft only")
    _require(
        {record["artifact_type_id"] for record in manifest["artifact_trace"]} == set(SCOPE_IDS)
        and len(manifest["artifact_trace"]) == 26,
        "manifest AIML artifact trace coverage differs",
    )
    _require(
        all(
            record["verification_status"] == "NOT_RUN"
            and record["approval_status"] == "NOT_APPROVED"
            and record["common_policy_refs"]
            for record in manifest["artifact_trace"]
        ),
        "manifest AIML artifact trace boundary differs",
    )
    explicit = {
        code
        for entry in manifest["generated_files"]
        for code in entry["artifact_type_ids"]
    }
    _require(explicit == materialized, "generated files do not cover exact Draft scope")
    _require(all(set(entry["artifact_type_ids"]) <= materialized for entry in manifest["generated_files"]), "Planned type claimed as Draft")

    for path, spec in DOCUMENT_SCOPE.items():
        text = outputs[path].decode("utf-8")
        header = "\n".join(text.splitlines()[:16])
        for code in spec["draft_ids"]:
            _require(code in header, f"Draft code missing from header: {code}")
        for code in set(spec["all_ids"]) - set(spec["draft_ids"]):
            _require(code not in header, f"Planned code leaked into Draft header: {code}")
        for code in spec["all_ids"]:
            _require(text.count(f'<a id="{code.lower()}"></a>') == 1, f"anchor count differs: {code}")
            section_start = text.index(f'<a id="{code.lower()}"></a>')
            next_start = text.find('<a id="aiml-', section_start + 20)
            section = text[section_start:] if next_start < 0 else text[section_start:next_start]
            expected_state = "DRAFT" if code in MATERIALIZED_IDS else "PLANNED"
            _require(f"| 산출물 상태 | {expected_state} |" in section, f"state differs: {code}")
            _require("| 실제 실행 | NOT_RUN |" in section, f"execution boundary missing: {code}")

    evaluation = json.loads(outputs[EVALUATION_REGISTER_PATH])
    _require(evaluation["formal_execution_count"] == 0, "formal evaluation count must stay zero")
    _require(evaluation["pass_count"] == 0, "formal PASS count must stay zero")
    _require(
        {item["artifact_type_id"] for item in evaluation["planned_executions"]} == set(PLANNED_IDS),
        "planned evaluation register coverage differs",
    )
    _require(
        all(
            item["execution_status"] == "NOT_RUN"
            and item["result"] is None
            and not item["evidence_ids"]
            and item["pass_claimed"] is False
            and item["execution_contract"] == PLANNED_EXECUTION_CONTRACTS[item["artifact_type_id"]]
            for item in evaluation["planned_executions"]
        ),
        "planned result boundary differs",
    )
    _require(len(evaluation["remaining_gates"]) == 5, "five gates missing")
    _require(all(gate["status"] == "NOT_RUN" and gate["waived"] is False for gate in evaluation["remaining_gates"]), "gate state changed")

    datasets = json.loads(outputs[DATASET_REGISTER_PATH])
    _require(datasets["formal_dataset_count"] == 0 and datasets["approved_dataset_count"] == 0, "dataset approval overclaimed")
    _require(
        datasets["runtime_class_schema_exact_model_ids"] == list(RUNTIME_MODEL_IDS)
        and [schema["class_count"] for schema in datasets["runtime_class_schemas"]] == [13, 3, 80],
        "AIML-04 runtime class schema coverage differs",
    )
    _require(
        all(
            schema["approval_status"] == "NOT_APPROVED"
            and schema["breaking_changes"]["migration_status"] == "NOT_RUN"
            and schema["breaking_changes"]["conversion_equivalence_revalidation_status"] == "NOT_RUN"
            for schema in datasets["runtime_class_schemas"]
        ),
        "AIML-04 migration boundary differs",
    )
    models = json.loads(outputs[MODEL_REGISTER_PATH])
    _require(models["approved_model_count"] == 0 and models["deployed_release_model_count"] == 0, "model approval overclaimed")
    _require(
        models["runtime_model_exact_ids"] == list(RUNTIME_MODEL_IDS)
        and {model["runtime_model_id"] for model in models["models"]} == set(RUNTIME_MODEL_IDS)
        and len(models["models"]) == 3,
        "AIML-14 runtime model exact set differs",
    )
    _require(all(model["deployment_eligible"] is False for model in models["models"]), "candidate made deployment eligible")
    _require(
        all(
            model["approval_status"] == "NOT_APPROVED"
            and model["evaluation_status"] == "NOT_RUN"
            and model["conversion_equivalence_status"] == "NOT_RUN"
            and model["android_device_performance_status"] == "NOT_RUN"
            and model["release_status"] == RELEASE_STATUS
            for model in models["models"]
        ),
        "AIML-14 evidence or release boundary differs",
    )
    _require(
        models["unknown_provenance_model_ids"] == ["custom_tactile", "coco_general"]
        and all(
            model["source_artifact"]["provenance_status"] == "UNKNOWN_PROVENANCE"
            for model in models["models"]
            if model["runtime_model_id"] in models["unknown_provenance_model_ids"]
        ),
        "fallback provenance was fabricated",
    )
    experiments = json.loads(outputs[EXPERIMENT_REGISTER_PATH])
    _require(experiments["formal_execution_count"] == 0 and not experiments["formal_experiments"], "experiment execution overclaimed")
    operations = json.loads(outputs[OPERATIONS_REGISTER_PATH])
    _require(operations["formal_operation_count"] == 0, "operation execution overclaimed")
    _require(manifest["authorization_boundary"]["release_status"] == RELEASE_STATUS, "release boundary differs")
    _require(manifest["authorization_boundary"]["remaining_gates_waived"] is False, "gates were waived")
    _require(
        manifest["planned_execution_contracts"] == PLANNED_EXECUTION_CONTRACTS,
        "manifest planned execution contracts differ",
    )
    _require(
        manifest["sensitive_data_boundary"]["fp035_network_issue_status"] == FP035_NORMALIZATION_STATUS
        and manifest["sensitive_data_boundary"]["fp035_normalized_policy"] == FP035_NORMALIZED_POLICY
        and manifest["sensitive_data_boundary"]["fp035_related_test_status"] == "NOT_RUN"
        and manifest["sensitive_data_boundary"]["fp035_correction_candidate_binding"]
        == _source_binding(FP035_CORRECTION_CANDIDATE_PATH)
        and manifest["sensitive_data_boundary"]["fp035_correction_candidate_approval_status"]
        == "NOT_APPROVED"
        and manifest["sensitive_data_boundary"]["fp035_correction_candidate_effective_status"]
        == "NOT_EFFECTIVE"
        and manifest["sensitive_data_boundary"]["fp035_policy_effect_claimed"] is False,
        "FP-035 normalized policy boundary differs",
    )


def generate() -> dict[Path, bytes]:
    outputs = _build_outputs()
    _validate_generated_outputs(outputs)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return outputs


def check() -> dict[Path, bytes]:
    outputs = _build_outputs()
    _validate_generated_outputs(outputs)
    for path, expected in outputs.items():
        _require(path.is_file(), f"generated output is missing: {_rel(path)}")
        _require(path.read_bytes() == expected, f"generated output is stale: {_rel(path)}")
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify committed outputs without rewriting")
    args = parser.parse_args()
    try:
        outputs = check() if args.check else generate()
    except (FormalAIMLError, OSError, approval_builder.BaselineApprovalError, alignment_builder.DecisionAlignmentError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    print(
        f"{action} {len(outputs)} AIML files; "
        f"materialized={len(MATERIALIZED_IDS)}, planned={len(PLANNED_IDS)}, "
        "formal executions=0, gates=5 NOT_RUN, release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
