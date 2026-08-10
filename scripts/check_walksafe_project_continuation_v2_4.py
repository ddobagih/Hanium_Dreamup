#!/usr/bin/env python3
"""Validate the WalkSafe v2.4 successor continuation boundary.

The checker preserves every frozen v2.3 byte and validates:

* exact v2.3 control files and active seq17 archive;
* v2.4 PACKAGE_PREPARED / PACKAGE_ACTIVATED / FP011 GOAL_STARTED boundary;
* a Goal-ID-independent replay automaton for every later append-only event.

The prepared SHA-256 is finalized with the candidate. The authorization
SHA-256 is finalized only after an exact manifest/seq1-bound user response.
The activation event is then protected by that authorization, its receipts,
and the append-only event hash chain rather than a self-referential source
constant inside the controlled working snapshot.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
import os
import re
import stat
import sys
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Mapping


ROOT = Path(__file__).resolve().parents[1]


V23_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3"
V23_PLAN_VERSION = "2.3.0"
V23_MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-3/"
    "static-plan-manifest-v2.3.0.json"
)
V23_MANIFEST_SHA256 = (
    "dfa615686b0223826497fae424c1f3c41538b271102879a9497a33b81f66329c"
)
V23_ARCHIVE_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "superseded-v2.3.0-active-checkpoint.json"
)
V23_ARCHIVE_RAW_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)
V23_EVENT_COUNT = 17
V23_TAIL_SHA256 = (
    "bc71126a8a0b71a97ce4cc89a86e0ce1d89739453f719f8820dc882c74a101d6"
)
V23_FROZEN_FILE_SHA256 = {
    "scripts/build_walksafe_goal_graph_v2_3.py": (
        "dce38036c0fb397e03f9fef47a0b78c329fa68de398500e7e5e6bdc0b0ec3c8f"
    ),
    "scripts/check_walksafe_goal_graph_v2_3.py": (
        "27dde08c2f7828fc138b9f502a31d604fde60c3957e33e87882ee9f05dbb87ac"
    ),
    "scripts/check_walksafe_project_continuation_v2_3.py": (
        "1785a97c5fd0cc0182cb1a9f95616328c344aca777f2e86839980afbe7bdab3f"
    ),
    "tests/test_walksafe_goal_graph_v2_3.py": (
        "a7e9762baf99ff41ee4ee3c5c7230d7d1f90d1d2b4e72fb0859d09d6eeabfe53"
    ),
    "tests/test_walksafe_project_continuation_v2_3.py": (
        "f2de52ef345d62b955552054aecd5be5bd832b4085e0f7114181a5c78b37252e"
    ),
}

V24_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
V24_PLAN_VERSION = "2.4.0"
V24_MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
V24_CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
FP011_GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
V24_CORE_PATHS = (
    "scripts/build_walksafe_goal_graph_v2_4.py",
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "scripts/check_walksafe_project_continuation_v2_4.py",
    "tests/test_walksafe_epic02_trace_v2_3_history.py",
    "tests/test_walksafe_goal_graph_v2_3_history.py",
    "tests/test_walksafe_goal_graph_v2_4.py",
    "tests/test_walksafe_project_continuation_v2_4.py",
)
V24_NATIVE_PATHS = (
    "docs/control/goals/walksafe-completion-graph-v2-4/README.md",
    (
        "docs/control/goals/walksafe-completion-graph-v2-4/"
        "active-supersession-record-v2.3.0.json"
    ),
    (
        "docs/control/goals/walksafe-completion-graph-v2-4/"
        "static-plan-manifest-v2.4.0.json"
    ),
    (
        "docs/control/goals/walksafe-completion-graph-v2-4/"
        "superseded-v2.3.0-active-checkpoint.json"
    ),
    (
        "docs/control/goals/walksafe-completion-graph-v2-4/"
        "templates/dynamic-node-template.md"
    ),
    (
        "docs/control/goals/walksafe-completion-graph-v2-4/"
        "templates/policy-gap-work-item.md"
    ),
)


def _load_frozen_v23_utility(root: Path = ROOT):
    path = root / "scripts/check_walksafe_project_continuation_v2_3.py"
    expected = V23_FROZEN_FILE_SHA256[path.relative_to(root).as_posix()]
    if (
        not path.is_file()
        or hashlib.sha256(path.read_bytes()).hexdigest() != expected
    ):
        raise RuntimeError("frozen v2.3 continuation utility SHA-256 differs")
    spec = importlib.util.spec_from_file_location(
        "_walksafe_v23_continuation_utility_for_v24",
        path,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("frozen v2.3 continuation utility cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_v23_utility = _load_frozen_v23_utility()
EXPECTED_CONTROLLED_PATHS = tuple(
    sorted(
        set(_v23_utility.EXPECTED_CONTROLLED_PATHS)
        | set(V24_CORE_PATHS)
        | set(V24_NATIVE_PATHS)
    )
)
EXPECTED_CONTROLLED_PATH_COUNT = 498
if len(EXPECTED_CONTROLLED_PATHS) != EXPECTED_CONTROLLED_PATH_COUNT:
    raise RuntimeError(
        "v2.4 activation controlled path count must be exactly 498"
    )
EXPECTED_CONTROLLED_PATH_SET_SHA256 = hashlib.sha256(
    (
        "\n".join(EXPECTED_CONTROLLED_PATHS) + "\n"
    ).encode("utf-8")
).hexdigest()

EXPECTED_V24_MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
EXPECTED_V24_PREPARED_EVENT_SHA256 = (
    "58c2b31637b5355609db14bf9e0779d4fd33d99f97a692866718a51fa64875d9"
)
EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256 = (
    "6a9220c38628067bbab547369ebd5b4540bb095ff39fb2c796e2bd554e967daa"
)
EXPECTED_V24_ACTIVATION_AUTHORIZATION_LITERAL = (
    f"{V24_PACKAGE_ID}의 manifest SHA-256 "
    f"{EXPECTED_V24_MANIFEST_SHA256} 및 PACKAGE_PREPARED seq1 SHA-256 "
    f"{EXPECTED_V24_PREPARED_EVENT_SHA256}에 결속해 활성화를 승인합니다."
)
V24_SEQ39_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-"
    "MUTABLE-CANONICAL-REFRESH-20260729-001"
)
V24_SEQ39_SOURCE_EVENT_SHA256 = (
    "aca93931b1cd8dcc06508f4078a7983d729df43f6a6690fceb1b3e875090152f"
)
V24_SEQ39_AUTHORIZATION_REQUEST_RELATIVE = Path(
    f"docs/control/execution/goal-gates/{V24_SEQ39_EVENT_ID}/"
    "authorization-request.json"
)
V24_SEQ39_AUTHORIZATION_REQUEST_SHA256 = (
    "b2c616e7e5f6a577b2c548fe28d3907887f1adebbcaa5bc6626c6745c374ed3d"
)
V24_SEQ39_AUTHORIZATION_REQUEST_BYTE_COUNT = 6627
V24_SEQ39_AUTHORIZATION_REQUEST_NONSELF_SHA256 = (
    "d4e96da364419826ccc3f5e4de76d141fbabbe628bc241d90df70d07541cf137"
)
V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_RELATIVE = Path(
    f"docs/control/execution/goal-gates/{V24_SEQ39_EVENT_ID}/"
    "authorization-request-independent-review-r001.md"
)
V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_SHA256 = (
    "925c8bdb547c1dffa6da581362079fba7d417da74c35972c53f79fbaad1e59ed"
)
V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_BYTE_COUNT = 3683
V24_SEQ39_AUTHORIZATION_RELATIVE = Path(
    f"docs/control/execution/goal-gates/{V24_SEQ39_EVENT_ID}/"
    "authorization.json"
)
V24_SEQ39_AUTHORIZATION_DOCUMENT_ID = (
    "WS-V24-SEQ39-CANONICAL-BINDING-UPDATE-AUTHORIZATION-20260729-001"
)
V24_SEQ39_AUTHORIZATION_ROLE = "CANONICAL_BINDING_UPDATE_AUTHORIZATION"
V24_SEQ39_SOURCE_CHECKPOINT_SHA256 = (
    "e61d919b3995f364760007c43c7bc462f1fd64f401b30d2f1f7ceeda86ab7e72"
)
V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT = 1291260
V24_SEQ39_SOURCE_CHECKPOINT_SCHEMA_VERSION = "1.24.0"
V24_SEQ39_TARGET_CHECKPOINT_SCHEMA_VERSION = "1.25.0"
V24_SEQ39_SOURCE_WORKING_PATH_SET_SHA256 = (
    "e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1"
)
V24_SEQ39_SOURCE_WORKING_CONTENT_SET_SHA256 = (
    "60eba216d34e53dea2aface304738ebb07b0067aaacdd38938ed13f99aa3a215"
)
V24_SEQ39_AUTHORIZED_AT = "2026-07-29T09:09:45+09:00"
V24_SEQ39_AUTHORIZATION_GENERATED_AT = "2026-07-29T09:09:46+09:00"
V24_SEQ39_OCCURRED_AT = "2026-07-29T09:09:47+09:00"
V24_SEQ39_OCCURRED_ON = "2026-07-29"
V24_SEQ39_EXACT_BINDING_UPDATES_SHA256 = (
    "31b18309818b1b8c869694d0532a5becfef59895b52aee4abdf67b9fa710b307"
)
V24_SEQ39_AUTHORIZATION_SCOPE_SHA256 = (
    "e46995d2bfb27949fd8a3c9574f6854355c0546d5ad1dd3821c3f912be477838"
)
V24_SEQ39_ACCEPTED_RESPONSE_SHA256 = (
    "7db70ea6b639a9df50be4e6c370088e5cd21bcfa3a5caa07e608a81a7782efb8"
)
V24_SEQ39_REJECTED_RESPONSE_SHA256 = (
    "551d0ee27c3b6b64a7ed49a871fb5d9f12628749f9ef41e42c96622f1f0b1843"
)
V24_SEQ39_AUTHORIZATION_QUESTION = (
    f"`{V24_SEQ39_EVENT_ID}`에서 위 5개 before→after SHA-256을 "
    "WalkSafe v2.4 정본 binding으로만 갱신하는 것을 승인하시겠습니까? "
    "이 승인은 파일 내용의 적합성, 시험 통과 또는 출시 승인을 뜻하지 "
    "않습니다. 답변: `승인합니다` 또는 `승인하지 않습니다`."
)
V24_SEQ39_EXACT_BINDING_UPDATES = [
    {
        "role": "ARTIFACT_CHANGE_LOG",
        "document_id": "ART-DOC-05-001",
        "path": "docs/deliverables/00-control/artifact-change-log.json",
        "before_sha256": (
            "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c"
        ),
        "before_byte_count": 96734,
        "after_sha256": (
            "cea8b58fc5afa527614522481df56d69618c6fd1015c10b63d6cc8b6e0678c67"
        ),
        "after_byte_count": 102892,
    },
    {
        "role": "ARTIFACT_REGISTER",
        "document_id": "ART-DOC-01-001",
        "path": "docs/deliverables/00-control/artifact-register.json",
        "before_sha256": (
            "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6"
        ),
        "before_byte_count": 3499550,
        "after_sha256": (
            "a0c993257c1b80211c2e8e4db5aa464708ac4aba9d47ed9b434eda277ee6547f"
        ),
        "after_byte_count": 3803696,
    },
    {
        "role": "DESIGN_TRACEABILITY",
        "document_id": "WS-DESIGN-TRACEABILITY-20260721-001",
        "path": (
            "docs/deliverables/04-design/design-traceability-register.json"
        ),
        "before_sha256": (
            "1ffb5861887d8edb26efbf0019cd4547baa6a24d8a73a576c3a5e9adf1e892aa"
        ),
        "before_byte_count": 459175,
        "after_sha256": (
            "18775a3f4d5d1faf0ae692b27888613c3d43f0b276d5a71dfbb08aad47c6b4ae"
        ),
        "after_byte_count": 463652,
    },
    {
        "role": "MODULE_REGISTER",
        "document_id": "DEV-18",
        "path": "docs/deliverables/05-implementation/module-register.json",
        "before_sha256": (
            "54f2119ccb8598f06261590f8855c8d4c442cd662c4502501b62164a2cc0fe49"
        ),
        "before_byte_count": 62222,
        "after_sha256": (
            "c0727ae24e53ce3142aa5c55db7d6273628655069c3ac0821fa7d03ad8f08b48"
        ),
        "after_byte_count": 62222,
    },
    {
        "role": "PLANNED_TEST_CASES",
        "document_id": "TST-05",
        "path": "docs/deliverables/06-testing/registers/test-cases.json",
        "before_sha256": (
            "19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee"
        ),
        "before_byte_count": 2590342,
        "after_sha256": (
            "fc51836c1dddd8852a74805e7fc5b1f6e647f461168139ae2565318745f87f2e"
        ),
        "after_byte_count": 2592818,
    },
]
V24_SEQ39_UNCHANGED_RTM_BINDING = {
    "role": "REQUIREMENTS_TRACEABILITY",
    "document_id": "WS-REQ-RTM-DRAFT-20260721-R001",
    "path": "docs/deliverables/03-requirements/rtm.json",
    "sha256": (
        "1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd"
    ),
    "byte_count": 1903186,
}
V24_SEQ39_AUTHORIZED_CHECKPOINT_MUTATIONS = [
    "APPEND_EXACT_SEQ39_EVENT",
    "UPDATE_CHECKPOINT_SCHEMA_VERSION_TO_1.25.0",
    "UPDATE_TOP_LEVEL_CANONICAL_BINDING_SHA256_FOR_EXACT_FIVE_ROLES",
    "UPDATE_ARTIFACT_QUEUE_SOURCE_BINDING_SHA256_ONLY",
    (
        "PROJECT_SEQ39_RUNTIME_WITH_UNCHANGED_GOAL_STATUSES_"
        "AND_ARTIFACT_COUNTS"
    ),
    "UPDATE_TRANSITION_HISTORY_ANCHOR",
    "UPDATE_GOAL_EXECUTION_VALIDATION_CUTOFF_TO_SEQ39_OCCURRED_AT",
    "UPDATE_WORKING_SNAPSHOT_HASHES",
]

# Reuse the frozen v2.3 implementation for deterministic read-only utilities.
working_snapshot_hashes = _v23_utility.working_snapshot_hashes
capture_gate_repository_state = _v23_utility.capture_gate_repository_state
current_head = _v23_utility.current_head

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
SAFE_ID_RE = re.compile(r"^[A-Za-z0-9._-]+$")
RUNTIME_STATUSES = {
    "PLANNED",
    "READY",
    "IN_PROGRESS",
    "AWAITING_USER",
    "AWAITING_EXTERNAL",
    "BLOCKED",
    "COMPLETE_AT_TARGET",
    "SUPERSEDED",
}
ALLOWED_EVENT_TYPES = {
    "PACKAGE_PREPARED",
    "PACKAGE_ACTIVATED",
    "CANONICAL_BINDINGS_UPDATED",
    "GOAL_MATERIALIZED",
    "GOAL_READY",
    "GOAL_STARTED",
    "WORK_SESSION_RESUMED",
    "GOAL_COMPLETED",
    "GOAL_FOCUS_CHANGED",
    "GOAL_SUPERSEDED",
    "BLOCKER_RECORDED",
    "BLOCKER_RESOLVED",
    "PACKAGE_COMPLETED",
}
V24_ACTIVATION_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "imported_completion_evidence_refs_by_goal",
    "imported_completion_evidence_bindings_by_goal",
    "imported_completion_event_sha256_by_goal",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "repository_snapshot_before",
    "package_activation_authorization_binding",
    "activation_quick_gate_binding",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "event_sha256",
}
V24_QUICK_GATE_RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "status",
    "package_id",
    "target_transition_event_id",
    "static_plan_manifest_sha256",
    "authorization_receipt_binding",
    "check_command_contract_version",
    "check_command_contract_sha256",
    "execution_window",
    "check_runs",
    "repository_snapshot",
    "generated_at",
}
V24_START_GATE_RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "gate_purpose",
    "status",
    "package_id",
    "target_transition_event_id",
    "target_goal_id",
    "target_goal_content_sha256",
    "static_plan_manifest_sha256",
    "source_activation_event_sha256",
    "check_command_contract_version",
    "check_command_contract_sha256",
    "toolchain_lock_binding",
    "execution_window",
    "check_runs",
    "repository_snapshot",
    "generated_at",
}
FP008_GOAL_ID = "WS-GOAL-EPIC-03-FP-008-R001"
FP008_START_GATE_CONTRACT_PATH = (
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-008-R001/"
    "initial-start-gate-contract-r001.json"
)
FP008_START_GATE_CONTRACT_FIELDS = {
    "schema_version",
    "document_id",
    "contract_id",
    "contract_version",
    "target_goal_id",
    "target_goal_content_sha256",
    "gate_purpose",
    "ordered_checks",
}
FP008_START_GATE_CONTRACT_BINDING_FIELDS = {
    "schema_version",
    "document_id",
    "path",
    "file_sha256",
    "contract_id",
    "contract_version",
    "canonical_contract_sha256",
}
FP008_START_GATE_CHECK_IDS = [
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_REPORTS_OPENAPI_POSTGRES",
    "ANDROID_USER_INTERNAL",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_FP008_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
]
FP008_START_GATE_RUNTIME_PATHS = [
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/app/gradle.lockfile",
    "apps/android/adminapp/gradle.lockfile",
]
FP046_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
FP046_START_GATE_CONTRACT_PATH = (
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-046-R001/"
    "initial-start-gate-contract-r001.json"
)
FP046_START_GATE_CHECK_IDS = [
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_REPORT_STORAGE_RETENTION_POSTGRES",
    "ANDROID_USER_INTERNAL",
    "ANDROID_GATEWAY_PRIVACY_INTERNAL",
    "ROOT_FP046_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
]
FP046_START_GATE_RUNTIME_PATHS = [
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/app/gradle.lockfile",
    "apps/android-gateway/package-lock.json",
    "configs/walksafe_node_toolchain_lock_20260715.json",
]
FP008_GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
FP008_PRIVATE_EVENT_DIRECTORY_MODE = 0o700
FP008_PRIVATE_EVIDENCE_FILE_MODE = 0o600
FP008_PRIVATE_EVIDENCE_MAX_BYTES = 64 * 1024 * 1024
FP008_START_GATE_RECEIPT_FIELDS = {
    "schema_version",
    "document_id",
    "evidence_type",
    "gate_purpose",
    "status",
    "package_id",
    "target_transition_event_id",
    "target_goal_id",
    "target_goal_content_sha256",
    "static_plan_manifest_sha256",
    "source_activation_event_sha256",
    "source_ready_event_sha256",
    "source_checkpoint_sha256",
    "check_command_contract_version",
    "check_command_contract_sha256",
    "implementation_start_gate_contract_binding",
    "runtime_bindings",
    "execution_window",
    "check_runs",
    "repository_snapshot",
    "generated_at",
}
V24_FIRST_START_EVENT_FIELDS = {
    "sequence",
    "event_id",
    "event_type",
    "occurred_on",
    "occurred_at",
    "previous_focus_goal_id",
    "previous_focus_content_sha256",
    "focus_goal_id",
    "focus_goal_content_sha256",
    "subject_goal_id",
    "from_status",
    "to_status",
    "static_plan_manifest_sha256",
    "status_changes",
    "runtime_after",
    "repository_snapshot_before",
    "implementation_start_gate_binding",
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "previous_event_sha256",
    "event_sha256",
}


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as stream:
        value = json.load(stream)
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_json_sha256(
    value: Any,
    *,
    omit: set[str] | None = None,
) -> str:
    if isinstance(value, dict) and omit:
        value = {key: item for key, item in value.items() if key not in omit}
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def event_sha256(event: dict[str, Any]) -> str:
    return canonical_json_sha256(event, omit={"event_sha256"})


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def resolve_repo_file(root: Path, value: Any) -> Path | None:
    if not isinstance(value, (str, Path)):
        return None
    candidate_value = Path(value)
    try:
        resolved_root = root.resolve(strict=True)
        candidate = (
            candidate_value
            if candidate_value.is_absolute()
            else resolved_root / candidate_value
        )
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(resolved_root)
    except (OSError, RuntimeError, ValueError):
        return None
    return resolved if resolved.is_file() else None


def _contains_symlink(root: Path, relative: str) -> bool:
    current = root.resolve()
    for part in Path(relative).parts:
        current /= part
        if current.is_symlink():
            return True
    return False


def validate_seq39_canonical_binding_authorization_request(
    root: Path,
    checkpoint: dict[str, Any] | None = None,
) -> list[str]:
    """Validate the reviewed request without treating it as authorization."""
    errors: list[str] = []
    request_relative = V24_SEQ39_AUTHORIZATION_REQUEST_RELATIVE.as_posix()
    review_relative = (
        V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_RELATIVE.as_posix()
    )
    for label, relative in (
        ("request", request_relative),
        ("review", review_relative),
    ):
        if _contains_symlink(root, relative):
            errors.append(f"v2.4 seq39 authorization {label} uses a symlink")
    request_path = resolve_repo_file(root, request_relative)
    review_path = resolve_repo_file(root, review_relative)
    if request_path is None:
        errors.append("v2.4 seq39 authorization request is missing")
    if review_path is None:
        errors.append("v2.4 seq39 authorization request review is missing")
    if errors:
        return errors
    try:
        request_size = request_path.stat().st_size
        review_size = review_path.stat().st_size
    except OSError as exc:
        return [f"v2.4 seq39 authorization request cannot be read: {exc}"]
    if request_size != V24_SEQ39_AUTHORIZATION_REQUEST_BYTE_COUNT:
        errors.append(
            "v2.4 seq39 authorization request byte count differs"
        )
    if sha256_file(request_path) != V24_SEQ39_AUTHORIZATION_REQUEST_SHA256:
        errors.append("v2.4 seq39 authorization request SHA-256 differs")
    if review_size != V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_BYTE_COUNT:
        errors.append(
            "v2.4 seq39 authorization request review byte count differs"
        )
    if (
        sha256_file(review_path)
        != V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_SHA256
    ):
        errors.append(
            "v2.4 seq39 authorization request review SHA-256 differs"
        )
    try:
        request = load_json(request_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"v2.4 seq39 authorization request cannot be loaded: {exc}"
        ]

    expected_event_contract = {
        "sequence": 39,
        "event_id": V24_SEQ39_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "previous_event_sha256": V24_SEQ39_SOURCE_EVENT_SHA256,
        "source_checkpoint_path": V24_CHECKPOINT_RELATIVE.as_posix(),
        "source_checkpoint_sha256": V24_SEQ39_SOURCE_CHECKPOINT_SHA256,
        "source_checkpoint_byte_count": V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT,
        "focus_goal_id": "WS-GOAL-EPIC-03",
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
    }
    expected_scope = {
        "operation": "APPEND_ONLY_SEQ39_CANONICAL_BINDING_SHA256_REFRESH",
        "changed_binding_count": 5,
        "changed_binding_roles_are_exhaustive": True,
        "exact_binding_updates": V24_SEQ39_EXACT_BINDING_UPDATES,
        "exact_binding_updates_sha256": (
            V24_SEQ39_EXACT_BINDING_UPDATES_SHA256
        ),
        "requirements_traceability_unchanged": (
            V24_SEQ39_UNCHANGED_RTM_BINDING
        ),
        "canonical_document_content_mutation_authorized": False,
        "artifact_status_or_count_change_authorized": False,
        "authorized_checkpoint_mutations_if_accepted": (
            V24_SEQ39_AUTHORIZED_CHECKPOINT_MUTATIONS
        ),
    }
    expected_scope_integrity = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 recursive lexicographic JSON key order, compact form"
        ),
        "json_path": "$.authorization_scope",
        "sha256": V24_SEQ39_AUTHORIZATION_SCOPE_SHA256,
    }
    expected_response = {
        "question_utf8": V24_SEQ39_AUTHORIZATION_QUESTION,
        "accepted_literal_utf8": "승인합니다",
        "accepted_literal_sha256": V24_SEQ39_ACCEPTED_RESPONSE_SHA256,
        "rejected_literal_utf8": "승인하지 않습니다",
        "rejected_literal_sha256": V24_SEQ39_REJECTED_RESPONSE_SHA256,
        "canonicalization": "UTF-8_WITHOUT_TRAILING_NEWLINE",
        "authorization_binding_rule": (
            "AUTHORIZATION_MUST_BIND_REQUEST_PHYSICAL_SHA256_AND_"
            "EXACT_RESPONSE_SHA256"
        ),
    }
    expected_boundary = {
        "authorization_currently_granted": False,
        "accepted_response_authorizes_only_exact_scope": True,
        "file_content_suitability_approved": False,
        "test_pass_approved_or_claimed": False,
        "canonical_document_content_mutation_authorized": False,
        "artifact_status_or_count_change_authorized": False,
        "checkpoint_projection_is_mechanical_only": True,
        "artifact_complete_count_before": 126,
        "artifact_complete_count_after_if_accepted": 126,
        "artifact_open_count_before": 131,
        "artifact_open_count_after_if_accepted": 131,
        "artifact_completion_credit_delta": 0,
        "approval_credit_delta": 0,
        "formal_test_credit_delta": 0,
        "actual_event_credit_delta": 0,
        "release_status": "NOT_ELIGIBLE",
        "intended_use": (
            "EXACT_FIVE_CANONICAL_BINDING_SHA256_REFRESH_"
            "AUTHORIZATION_ONLY"
        ),
    }
    expected_integrity = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 recursive lexicographic JSON key order, compact form"
        ),
        "excluded_json_path": "$.integrity",
        "sha256": V24_SEQ39_AUTHORIZATION_REQUEST_NONSELF_SHA256,
    }
    expected_fields = {
        "schema_version",
        "request_id",
        "status",
        "requested_at",
        "event_contract",
        "authorization_scope",
        "authorization_scope_integrity",
        "response_contract",
        "claim_boundary",
        "integrity",
    }
    for label, actual, expected in (
        ("field set", set(request), expected_fields),
        (
            "schema",
            request.get("schema_version"),
            (
                "walksafe.canonical-binding-update-authorization-"
                "request.v1"
            ),
        ),
        (
            "request ID",
            request.get("request_id"),
            (
                "WS-V24-SEQ39-CANONICAL-BINDING-UPDATE-"
                "AUTHORIZATION-REQUEST-20260729-001"
            ),
        ),
        ("status", request.get("status"), "AWAITING_USER_AUTHORIZATION"),
        (
            "requested_at",
            request.get("requested_at"),
            "2026-07-29T03:09:16+09:00",
        ),
        (
            "event contract",
            request.get("event_contract"),
            expected_event_contract,
        ),
        (
            "authorization scope",
            request.get("authorization_scope"),
            expected_scope,
        ),
        (
            "authorization scope integrity",
            request.get("authorization_scope_integrity"),
            expected_scope_integrity,
        ),
        (
            "response contract",
            request.get("response_contract"),
            expected_response,
        ),
        (
            "claim boundary",
            request.get("claim_boundary"),
            expected_boundary,
        ),
        ("integrity", request.get("integrity"), expected_integrity),
    ):
        if actual != expected:
            errors.append(f"v2.4 seq39 authorization request {label} differs")
    if (
        canonical_json_sha256(V24_SEQ39_EXACT_BINDING_UPDATES)
        != V24_SEQ39_EXACT_BINDING_UPDATES_SHA256
        or canonical_json_sha256(expected_scope)
        != V24_SEQ39_AUTHORIZATION_SCOPE_SHA256
    ):
        errors.append(
            "v2.4 seq39 authorization request expected scope digest differs"
        )
    if (
        canonical_json_sha256(request, omit={"integrity"})
        != V24_SEQ39_AUTHORIZATION_REQUEST_NONSELF_SHA256
    ):
        errors.append(
            "v2.4 seq39 authorization request non-self digest differs"
        )
    if (
        hashlib.sha256("승인합니다".encode("utf-8")).hexdigest()
        != V24_SEQ39_ACCEPTED_RESPONSE_SHA256
        or hashlib.sha256("승인하지 않습니다".encode("utf-8")).hexdigest()
        != V24_SEQ39_REJECTED_RESPONSE_SHA256
    ):
        errors.append(
            "v2.4 seq39 authorization response literal digest differs"
        )

    if checkpoint is None:
        checkpoint_path = resolve_repo_file(root, V24_CHECKPOINT_RELATIVE)
        if checkpoint_path is None:
            errors.append(
                "v2.4 seq39 authorization source checkpoint is missing"
            )
            checkpoint = {}
        else:
            try:
                checkpoint = load_json(checkpoint_path)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                errors.append(
                    "v2.4 seq39 authorization source checkpoint cannot "
                    f"be loaded: {exc}"
                )
                checkpoint = {}
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    source_events = (
        [
            event
            for event in history
            if isinstance(event, dict)
            and event.get("sequence") == 38
            and event.get("event_sha256") == V24_SEQ39_SOURCE_EVENT_SHA256
        ]
        if isinstance(history, list)
        else []
    )
    seq39_events = (
        [
            event
            for event in history
            if isinstance(event, dict)
            and event.get("sequence") == 39
            and event.get("event_id") == V24_SEQ39_EVENT_ID
        ]
        if isinstance(history, list)
        else []
    )
    if len(source_events) != 1:
        errors.append(
            "v2.4 seq39 authorization source event anchor differs"
        )
    checkpoint_path = resolve_repo_file(root, V24_CHECKPOINT_RELATIVE)
    checkpoint_matches_source = False
    if checkpoint_path is not None:
        try:
            checkpoint_matches_source = (
                checkpoint_path.stat().st_size
                == expected_event_contract["source_checkpoint_byte_count"]
                and sha256_file(checkpoint_path)
                == expected_event_contract["source_checkpoint_sha256"]
            )
        except OSError:
            checkpoint_matches_source = False
    if not checkpoint_matches_source and len(seq39_events) != 1:
        errors.append(
            "v2.4 seq39 authorization source checkpoint binding differs"
        )

    seq39_snapshot = (
        seq39_events[0].get("canonical_binding_snapshot_after")
        if len(seq39_events) == 1
        else None
    )
    for update in V24_SEQ39_EXACT_BINDING_UPDATES:
        relative = update["path"]
        if _contains_symlink(root, relative):
            errors.append(
                "v2.4 seq39 authorization current binding uses a symlink: "
                f"{update['role']}"
            )
            continue
        if isinstance(seq39_snapshot, dict):
            binding = seq39_snapshot.get(update["role"])
            if (
                not isinstance(binding, dict)
                or binding.get("role") != update["role"]
                or binding.get("document_id") != update["document_id"]
                or binding.get("path") != relative
                or binding.get("file_sha256") != update["after_sha256"]
            ):
                errors.append(
                    "v2.4 seq39 authorization event binding differs: "
                    f"{update['role']}"
                )
            continue
        live = resolve_repo_file(root, relative)
        if live is None:
            errors.append(
                "v2.4 seq39 authorization current binding is missing: "
                f"{update['role']}"
            )
            continue
        try:
            live_size = live.stat().st_size
        except OSError:
            errors.append(
                "v2.4 seq39 authorization current binding is unreadable: "
                f"{update['role']}"
            )
            continue
        if (
            live_size != update["after_byte_count"]
            or sha256_file(live) != update["after_sha256"]
        ):
            errors.append(
                "v2.4 seq39 authorization current binding differs: "
                f"{update['role']}"
            )

    rtm = V24_SEQ39_UNCHANGED_RTM_BINDING
    if isinstance(seq39_snapshot, dict):
        rtm_binding = seq39_snapshot.get(rtm["role"])
        if (
            not isinstance(rtm_binding, dict)
            or rtm_binding.get("role") != rtm["role"]
            or rtm_binding.get("document_id") != rtm["document_id"]
            or rtm_binding.get("path") != rtm["path"]
            or rtm_binding.get("file_sha256") != rtm["sha256"]
        ):
            errors.append(
                "v2.4 seq39 authorization unchanged RTM binding differs"
            )
    else:
        rtm_path = resolve_repo_file(root, rtm["path"])
        try:
            rtm_size = rtm_path.stat().st_size if rtm_path is not None else None
        except OSError:
            rtm_size = None
        if (
            rtm_path is None
            or _contains_symlink(root, rtm["path"])
            or rtm_size != rtm["byte_count"]
            or sha256_file(rtm_path) != rtm["sha256"]
        ):
            errors.append(
                "v2.4 seq39 authorization unchanged RTM binding differs"
            )
    return errors


def _json_document_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")


def expected_seq39_authorization_receipt() -> dict[str, Any]:
    scope = {
        "operation": "APPEND_ONLY_SEQ39_CANONICAL_BINDING_SHA256_REFRESH",
        "changed_binding_count": 5,
        "changed_binding_roles_are_exhaustive": True,
        "exact_binding_updates": copy.deepcopy(
            V24_SEQ39_EXACT_BINDING_UPDATES
        ),
        "exact_binding_updates_sha256": (
            V24_SEQ39_EXACT_BINDING_UPDATES_SHA256
        ),
        "requirements_traceability_unchanged": copy.deepcopy(
            V24_SEQ39_UNCHANGED_RTM_BINDING
        ),
        "authorized_checkpoint_mutations": copy.deepcopy(
            V24_SEQ39_AUTHORIZED_CHECKPOINT_MUTATIONS
        ),
    }
    receipt: dict[str, Any] = {
        "schema_version": (
            "walksafe.canonical-binding-update-authorization.v1"
        ),
        "document_id": V24_SEQ39_AUTHORIZATION_DOCUMENT_ID,
        "evidence_type": "CANONICAL_BINDING_UPDATE_AUTHORIZATION",
        "status": "AUTHORIZED",
        "package_id": V24_PACKAGE_ID,
        "target_event": {
            "sequence": 39,
            "event_id": V24_SEQ39_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "previous_event_sha256": V24_SEQ39_SOURCE_EVENT_SHA256,
            "source_checkpoint_path": V24_CHECKPOINT_RELATIVE.as_posix(),
            "source_checkpoint_sha256": V24_SEQ39_SOURCE_CHECKPOINT_SHA256,
            "source_checkpoint_byte_count": (
                V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT
            ),
        },
        "authorization_request_binding": {
            "request_id": (
                "WS-V24-SEQ39-CANONICAL-BINDING-UPDATE-"
                "AUTHORIZATION-REQUEST-20260729-001"
            ),
            "path": V24_SEQ39_AUTHORIZATION_REQUEST_RELATIVE.as_posix(),
            "physical_sha256": V24_SEQ39_AUTHORIZATION_REQUEST_SHA256,
            "byte_count": V24_SEQ39_AUTHORIZATION_REQUEST_BYTE_COUNT,
            "authorization_scope_sha256": (
                V24_SEQ39_AUTHORIZATION_SCOPE_SHA256
            ),
        },
        "authorization_request_review_binding": {
            "path": (
                V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_RELATIVE.as_posix()
            ),
            "physical_sha256": (
                V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_SHA256
            ),
            "byte_count": V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_BYTE_COUNT,
            "verdict": "PASS_FOR_EXACT_SCOPE_USER_AUTHORIZATION_REQUEST",
            "blocking_findings": 0,
            "major_findings": 0,
            "minor_findings": 0,
        },
        "authorization_scope": scope,
        "timestamp_basis": (
            "LOCAL_SESSION_PROCESSING_TIME_AFTER_USER_RESPONSE"
        ),
        "authorized_at": V24_SEQ39_AUTHORIZED_AT,
        "generated_at": V24_SEQ39_AUTHORIZATION_GENERATED_AT,
        "user_response": {
            "literal_utf8": "승인합니다",
            "canonicalization": "UTF-8_WITHOUT_TRAILING_NEWLINE",
            "sha256": V24_SEQ39_ACCEPTED_RESPONSE_SHA256,
        },
        "claim_boundary": {
            "authorization_granted_for_exact_scope": True,
            "accepted_response_authorizes_only_exact_scope": True,
            "file_content_suitability_approved": False,
            "test_pass_approved_or_claimed": False,
            "canonical_document_content_mutation_authorized": False,
            "artifact_status_or_count_change_authorized": False,
            "checkpoint_projection_is_mechanical_only": True,
            "artifact_complete_count_before": 126,
            "artifact_complete_count_after": 126,
            "artifact_open_count_before": 131,
            "artifact_open_count_after": 131,
            "artifact_completion_credit_delta": 0,
            "approval_credit_delta": 0,
            "formal_test_credit_delta": 0,
            "actual_event_credit_delta": 0,
            "release_status": "NOT_ELIGIBLE",
            "intended_use": (
                "EXACT_FIVE_CANONICAL_BINDING_SHA256_REFRESH_"
                "AUTHORIZATION_ONLY"
            ),
        },
    }
    receipt["integrity"] = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 recursive lexicographic JSON key order, compact form"
        ),
        "excluded_json_path": "$.integrity",
        "sha256": canonical_json_sha256(receipt),
    }
    return receipt


def _seq39_register_update() -> dict[str, Any]:
    return next(
        copy.deepcopy(update)
        for update in V24_SEQ39_EXACT_BINDING_UPDATES
        if update["role"] == "ARTIFACT_REGISTER"
    )


def _seq39_snapshot_after(
    source_checkpoint: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    snapshot = canonical_binding_snapshot(source_checkpoint)
    if not snapshot:
        raise ValueError("seq39 source canonical snapshot is missing")
    for update in V24_SEQ39_EXACT_BINDING_UPDATES:
        binding = snapshot.get(update["role"])
        if (
            not isinstance(binding, dict)
            or binding.get("document_id") != update["document_id"]
            or binding.get("path") != update["path"]
            or binding.get("file_sha256") != update["before_sha256"]
        ):
            raise ValueError(
                "seq39 source canonical binding differs: "
                f"{update['role']}"
            )
        binding["file_sha256"] = update["after_sha256"]
    return snapshot


def expected_seq39_event(
    source_checkpoint: dict[str, Any],
    authorization_sha256: str,
) -> dict[str, Any]:
    if not SHA256_RE.fullmatch(authorization_sha256):
        raise ValueError("seq39 authorization SHA-256 is invalid")
    state = source_checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    if not isinstance(state, dict) or not isinstance(history, list):
        raise ValueError("seq39 source history is missing")
    if len(history) != 38 or not isinstance(history[-1], dict):
        raise ValueError("seq39 source history length differs")
    source_event = history[-1]
    if (
        source_event.get("sequence") != 38
        or source_event.get("event_sha256")
        != V24_SEQ39_SOURCE_EVENT_SHA256
        or state.get("transition_history_anchor_sha256")
        != V24_SEQ39_SOURCE_EVENT_SHA256
        or source_checkpoint.get("schema_version")
        != V24_SEQ39_SOURCE_CHECKPOINT_SCHEMA_VERSION
        or state.get("status_by_goal", {}).get("WS-GOAL-EPIC-03")
        != "READY"
    ):
        raise ValueError("seq39 source checkpoint boundary differs")
    source_snapshot = source_event.get("canonical_binding_snapshot_after")
    if source_snapshot != canonical_binding_snapshot(source_checkpoint):
        raise ValueError("seq39 source canonical projections differ")
    runtime = copy.deepcopy(source_event.get("runtime_after"))
    if not isinstance(runtime, dict):
        raise ValueError("seq39 source runtime is missing")
    register_update = _seq39_register_update()
    queue = runtime.get("artifact_work_queue")
    source_binding = (
        queue.get("source_binding") if isinstance(queue, dict) else None
    )
    if (
        not isinstance(source_binding, dict)
        or source_binding.get("file_sha256")
        != register_update["before_sha256"]
    ):
        raise ValueError("seq39 source queue binding differs")
    source_binding["file_sha256"] = register_update["after_sha256"]
    event: dict[str, Any] = {
        "sequence": 39,
        "event_id": V24_SEQ39_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_on": V24_SEQ39_OCCURRED_ON,
        "occurred_at": V24_SEQ39_OCCURRED_AT,
        "previous_focus_goal_id": "WS-GOAL-EPIC-03",
        "previous_focus_content_sha256": (
            "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
        ),
        "focus_goal_id": "WS-GOAL-EPIC-03",
        "focus_goal_content_sha256": (
            "7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832"
        ),
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": EXPECTED_V24_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": runtime,
        "blockers_after": copy.deepcopy(source_event.get("blockers_after")),
        "blocker_resolution_ids_after": copy.deepcopy(
            source_event.get("blocker_resolution_ids_after")
        ),
        "source_checkpoint_version": (
            V24_SEQ39_SOURCE_CHECKPOINT_SCHEMA_VERSION
        ),
        "evidence_refs": [
            V24_SEQ39_AUTHORIZATION_ROLE,
            "CANONICAL_BINDING_UPDATE_AUTHORIZATION_REQUEST",
            "CANONICAL_BINDING_UPDATE_AUTHORIZATION_REQUEST_REVIEW",
        ],
        "changed_binding_roles": [
            update["role"] for update in V24_SEQ39_EXACT_BINDING_UPDATES
        ],
        "exact_binding_updates": copy.deepcopy(
            V24_SEQ39_EXACT_BINDING_UPDATES
        ),
        "exact_binding_updates_sha256": (
            V24_SEQ39_EXACT_BINDING_UPDATES_SHA256
        ),
        "authorization_scope_sha256": (
            V24_SEQ39_AUTHORIZATION_SCOPE_SHA256
        ),
        "authorization_request_binding": {
            "request_id": (
                "WS-V24-SEQ39-CANONICAL-BINDING-UPDATE-"
                "AUTHORIZATION-REQUEST-20260729-001"
            ),
            "path": V24_SEQ39_AUTHORIZATION_REQUEST_RELATIVE.as_posix(),
            "file_sha256": V24_SEQ39_AUTHORIZATION_REQUEST_SHA256,
            "byte_count": V24_SEQ39_AUTHORIZATION_REQUEST_BYTE_COUNT,
        },
        "authorization_request_review_binding": {
            "path": (
                V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_RELATIVE.as_posix()
            ),
            "file_sha256": (
                V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_SHA256
            ),
            "byte_count": V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_BYTE_COUNT,
        },
        "canonical_binding_update_authorization_binding": {
            "role": V24_SEQ39_AUTHORIZATION_ROLE,
            "document_id": V24_SEQ39_AUTHORIZATION_DOCUMENT_ID,
            "path": V24_SEQ39_AUTHORIZATION_RELATIVE.as_posix(),
            "file_sha256": authorization_sha256,
        },
        "source_checkpoint_binding": {
            "path": V24_CHECKPOINT_RELATIVE.as_posix(),
            "file_sha256": V24_SEQ39_SOURCE_CHECKPOINT_SHA256,
            "byte_count": V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT,
        },
        "requirements_traceability_unchanged": copy.deepcopy(
            V24_SEQ39_UNCHANGED_RTM_BINDING
        ),
        "artifact_queue_projection_basis": {
            "mode": (
                "PRESERVED_SEQ38_PROJECTION_WITH_SOURCE_SHA_REFRESH_ONLY"
            ),
            "source_event_sha256": V24_SEQ39_SOURCE_EVENT_SHA256,
            "register_rederivation_performed": False,
            "register_rederivation_authorized": False,
            "current_register_projection_claimed": False,
        },
        "claim_boundary": {
            "canonical_document_content_mutation_performed": False,
            "artifact_status_or_count_changed": False,
            "artifact_complete_count": 126,
            "artifact_open_count": 131,
            "artifact_completion_credit_delta": 0,
            "approval_credit_delta": 0,
            "formal_test_credit_delta": 0,
            "actual_event_credit_delta": 0,
            "release_status": "NOT_ELIGIBLE",
        },
        "canonical_binding_snapshot_after": _seq39_snapshot_after(
            source_checkpoint
        ),
        "previous_event_sha256": V24_SEQ39_SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = event_sha256(event)
    return event


def project_seq39_checkpoint(
    source_checkpoint: dict[str, Any],
    authorization_sha256: str,
    *,
    working_path_set_sha256: str,
    working_content_set_sha256: str,
) -> dict[str, Any]:
    checkpoint = copy.deepcopy(source_checkpoint)
    event = expected_seq39_event(checkpoint, authorization_sha256)
    checkpoint["schema_version"] = V24_SEQ39_TARGET_CHECKPOINT_SCHEMA_VERSION
    updates = {
        update["role"]: update
        for update in V24_SEQ39_EXACT_BINDING_UPDATES
    }
    bindings = checkpoint.get("canonical_bindings")
    if not isinstance(bindings, list):
        raise ValueError("seq39 canonical bindings are missing")
    for binding in bindings:
        if not isinstance(binding, dict):
            raise ValueError("seq39 canonical binding is malformed")
        update = updates.get(binding.get("role"))
        if update is not None:
            binding["file_sha256"] = update["after_sha256"]
    state = checkpoint["goal_execution"]
    register_update = _seq39_register_update()
    state["artifact_work_queue"]["source_binding"]["file_sha256"] = (
        register_update["after_sha256"]
    )
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = V24_SEQ39_OCCURRED_AT
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot["path_set_sha256"] = working_path_set_sha256
    snapshot["content_set_sha256"] = working_content_set_sha256
    return checkpoint


def reverse_seq39_checkpoint(
    checkpoint: dict[str, Any],
) -> dict[str, Any]:
    source = copy.deepcopy(checkpoint)
    state = source.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    if (
        not isinstance(state, dict)
        or not isinstance(history, list)
        or len(history) != 39
        or not isinstance(history[-1], dict)
        or history[-1].get("event_id") != V24_SEQ39_EVENT_ID
    ):
        raise ValueError("seq39 checkpoint tail differs")
    history.pop()
    source["schema_version"] = V24_SEQ39_SOURCE_CHECKPOINT_SCHEMA_VERSION
    updates = {
        update["role"]: update
        for update in V24_SEQ39_EXACT_BINDING_UPDATES
    }
    bindings = source.get("canonical_bindings")
    if not isinstance(bindings, list):
        raise ValueError("seq39 canonical bindings are missing")
    for binding in bindings:
        if not isinstance(binding, dict):
            raise ValueError("seq39 canonical binding is malformed")
        update = updates.get(binding.get("role"))
        if update is not None:
            binding["file_sha256"] = update["before_sha256"]
    register_update = _seq39_register_update()
    state["artifact_work_queue"]["source_binding"]["file_sha256"] = (
        register_update["before_sha256"]
    )
    state["transition_history_anchor_sha256"] = V24_SEQ39_SOURCE_EVENT_SHA256
    state["validation_cutoff_at"] = history[-1]["occurred_at"]
    snapshot = source["working_tree_snapshot"]
    snapshot["path_set_sha256"] = V24_SEQ39_SOURCE_WORKING_PATH_SET_SHA256
    snapshot["content_set_sha256"] = (
        V24_SEQ39_SOURCE_WORKING_CONTENT_SET_SHA256
    )
    return source


def expected_seq39_event_from_history_prefix(
    checkpoint: dict[str, Any],
    authorization_sha256: str,
) -> dict[str, Any]:
    """Rebuild seq39 from its sealed seq1..38 historical prefix.

    Once later append-only events exist, the live checkpoint can no longer be
    byte-reversed to the exact seq38 checkpoint.  The seq38 event already
    seals the runtime and canonical snapshot needed to reproduce seq39, so
    validate that historical projection without treating the live tail as the
    authorized seq39 output.
    """
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    if (
        not isinstance(history, list)
        or len(history) < 39
        or not isinstance(history[37], dict)
        or not isinstance(history[38], dict)
        or history[37].get("sequence") != 38
        or history[37].get("event_sha256")
        != V24_SEQ39_SOURCE_EVENT_SHA256
        or history[38].get("sequence") != 39
        or history[38].get("event_id") != V24_SEQ39_EVENT_ID
    ):
        raise ValueError("seq39 historical prefix differs")
    source_snapshot = history[37].get("canonical_binding_snapshot_after")
    if not isinstance(source_snapshot, dict):
        raise ValueError("seq39 source canonical snapshot is missing")
    source = {
        "schema_version": V24_SEQ39_SOURCE_CHECKPOINT_SCHEMA_VERSION,
        "canonical_bindings": [
            copy.deepcopy(binding)
            for binding in source_snapshot.values()
        ],
        "goal_execution": {
            "transition_history": copy.deepcopy(history[:38]),
            "transition_history_anchor_sha256": (
                V24_SEQ39_SOURCE_EVENT_SHA256
            ),
            "status_by_goal": {"WS-GOAL-EPIC-03": "READY"},
        },
    }
    return expected_seq39_event(source, authorization_sha256)


def validate_seq39_canonical_binding_update(
    root: Path,
    checkpoint: dict[str, Any] | None = None,
) -> list[str]:
    """Validate the accepted literal, exact receipt, event, and projection."""
    errors: list[str] = []
    authorization_relative = V24_SEQ39_AUTHORIZATION_RELATIVE.as_posix()
    authorization_path = resolve_repo_file(root, authorization_relative)
    if checkpoint is None:
        checkpoint_path = resolve_repo_file(root, V24_CHECKPOINT_RELATIVE)
        if checkpoint_path is None:
            return ["v2.4 seq39 checkpoint is missing"]
        try:
            checkpoint = load_json(checkpoint_path)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            return [f"v2.4 seq39 checkpoint cannot be loaded: {exc}"]
    state = checkpoint.get("goal_execution")
    history = (
        state.get("transition_history")
        if isinstance(state, dict)
        else None
    )
    seq39_events = (
        [
            event
            for event in history
            if isinstance(event, dict)
            and (
                event.get("sequence") == 39
                or event.get("event_id") == V24_SEQ39_EVENT_ID
            )
        ]
        if isinstance(history, list)
        else []
    )
    if authorization_path is None and not seq39_events:
        return []
    if _contains_symlink(root, authorization_relative):
        errors.append("v2.4 seq39 authorization receipt uses a symlink")
    if authorization_path is None:
        errors.append("v2.4 seq39 authorization receipt is missing")
        return errors
    if len(seq39_events) != 1:
        errors.append("v2.4 seq39 event count differs")
        return errors
    try:
        authorization = load_json(authorization_path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [
            f"v2.4 seq39 authorization receipt cannot be loaded: {exc}"
        ]
    expected_authorization = expected_seq39_authorization_receipt()
    if authorization != expected_authorization:
        errors.append("v2.4 seq39 authorization receipt differs")
    if (
        canonical_json_sha256(authorization, omit={"integrity"})
        != authorization.get("integrity", {}).get("sha256")
    ):
        errors.append("v2.4 seq39 authorization receipt integrity differs")
    authorization_sha256 = sha256_file(authorization_path)

    seq39_is_live_tail = bool(
        isinstance(history, list)
        and len(history) == 39
        and history[-1] is seq39_events[0]
    )
    if seq39_is_live_tail:
        try:
            source = reverse_seq39_checkpoint(checkpoint)
        except (KeyError, TypeError, ValueError) as exc:
            return errors + [
                f"v2.4 seq39 reverse projection failed: {exc}"
            ]
        source_bytes = _json_document_bytes(source)
        if (
            len(source_bytes) != V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT
            or sha256_bytes(source_bytes)
            != V24_SEQ39_SOURCE_CHECKPOINT_SHA256
        ):
            errors.append(
                "v2.4 seq39 checkpoint exceeds the authorized mutation set"
            )

        snapshot = checkpoint.get("working_tree_snapshot")
        paths = (
            snapshot.get("managed_changed_paths")
            if isinstance(snapshot, dict)
            else None
        )
        if not isinstance(paths, list):
            return errors + [
                "v2.4 seq39 working snapshot paths are missing"
            ]
        try:
            path_hash, content_hash = working_snapshot_hashes(root, paths)
        except (OSError, RuntimeError, ValueError) as exc:
            return errors + [
                f"v2.4 seq39 working snapshot cannot be reproduced: {exc}"
            ]
        try:
            expected_checkpoint = project_seq39_checkpoint(
                source,
                authorization_sha256,
                working_path_set_sha256=path_hash,
                working_content_set_sha256=content_hash,
            )
        except (KeyError, TypeError, ValueError) as exc:
            return errors + [
                f"v2.4 seq39 expected projection failed: {exc}"
            ]
        if checkpoint != expected_checkpoint:
            errors.append("v2.4 seq39 checkpoint projection differs")
        expected_event = expected_checkpoint["goal_execution"][
            "transition_history"
        ][-1]
    else:
        try:
            expected_event = expected_seq39_event_from_history_prefix(
                checkpoint,
                authorization_sha256,
            )
        except (KeyError, TypeError, ValueError) as exc:
            return errors + [
                f"v2.4 seq39 historical projection failed: {exc}"
            ]
    if seq39_events[0] != expected_event:
        errors.append("v2.4 seq39 event differs")

    if seq39_is_live_tail:
        for update in V24_SEQ39_EXACT_BINDING_UPDATES:
            live = resolve_repo_file(root, update["path"])
            try:
                live_size = live.stat().st_size if live is not None else None
            except OSError:
                live_size = None
            if (
                live is None
                or _contains_symlink(root, update["path"])
                or live_size != update["after_byte_count"]
                or sha256_file(live) != update["after_sha256"]
            ):
                errors.append(
                    "v2.4 seq39 live canonical binding differs: "
                    f"{update['role']}"
                )
        rtm = V24_SEQ39_UNCHANGED_RTM_BINDING
        rtm_path = resolve_repo_file(root, rtm["path"])
        try:
            rtm_size = (
                rtm_path.stat().st_size if rtm_path is not None else None
            )
        except OSError:
            rtm_size = None
        if (
            rtm_path is None
            or _contains_symlink(root, rtm["path"])
            or rtm_size != rtm["byte_count"]
            or sha256_file(rtm_path) != rtm["sha256"]
        ):
            errors.append("v2.4 seq39 live RTM binding differs")
    return errors


def canonical_binding_snapshot(
    checkpoint: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    raw = checkpoint.get("canonical_bindings")
    if not isinstance(raw, list):
        return {}
    fields = ("role", "document_id", "path", "file_sha256")
    result: dict[str, dict[str, Any]] = {}
    for binding in raw:
        if not isinstance(binding, dict):
            continue
        role = binding.get("role")
        if isinstance(role, str) and role:
            result[role] = {
                field: binding.get(field)
                for field in fields
            }
    return dict(sorted(result.items()))


def package_hashes(
    root: Path,
    relative_paths: list[str],
) -> tuple[str, str]:
    return working_snapshot_hashes(root, relative_paths)


def expected_goal_paths(
    state: dict[str, Any],
) -> tuple[str, ...]:
    paths: set[str] = set()
    for field in (
        "imported_predecessor_goal_bindings",
        "dynamic_goal_inventory",
    ):
        records = state.get(field)
        if not isinstance(records, dict):
            continue
        paths.update(
            record["path"]
            for record in records.values()
            if isinstance(record, dict)
            and isinstance(record.get("path"), str)
        )
    return tuple(sorted(paths))


def validate_v24_manifest_and_package(
    root: Path,
    *,
    manifest: dict[str, Any],
    manifest_path: Path,
    state: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    _require_equal(
        errors,
        "v2.4 manifest package ID",
        manifest.get("package_id"),
        V24_PACKAGE_ID,
    )
    _require_equal(
        errors,
        "v2.4 manifest plan version",
        manifest.get("plan_version"),
        V24_PLAN_VERSION,
    )
    contract = manifest.get("imported_predecessor_goal_contract")
    if not isinstance(contract, dict):
        errors.append("v2.4 imported predecessor Goal contract is missing")
    else:
        expected_contract = {
            "source_package_id": V23_PACKAGE_ID,
            "source_plan_version": V23_PLAN_VERSION,
            "source_checkpoint_path": V23_ARCHIVE_RELATIVE.as_posix(),
            "runtime_binding_field": "imported_predecessor_goal_bindings",
            "expected_goal_count": 20,
            "goal_paths_remain_in_predecessor_packages": True,
            "goal_bytes_must_match_archived_projection": True,
            "materialization_and_completion_lineage_is_imported": True,
        }
        for key, expected in expected_contract.items():
            _require_equal(
                errors,
                f"v2.4 imported predecessor contract {key}",
                contract.get(key),
                expected,
            )
    control = manifest.get("successor_control_contract")
    expected_control_paths = {
        "builder_path": "scripts/build_walksafe_goal_graph_v2_4.py",
        "checker_path": "scripts/check_walksafe_goal_graph_v2_4.py",
        "test_path": "tests/test_walksafe_goal_graph_v2_4.py",
        "continuation_checker_path": (
            "scripts/check_walksafe_project_continuation_v2_4.py"
        ),
        "continuation_test_path": (
            "tests/test_walksafe_project_continuation_v2_4.py"
        ),
    }
    if not isinstance(control, dict):
        errors.append("v2.4 successor control contract is missing")
    else:
        for key, expected in expected_control_paths.items():
            _require_equal(
                errors,
                f"v2.4 successor control contract {key}",
                control.get(key),
                expected,
            )

    protected = manifest.get("protected_files")
    protected_map = (
        {
            row.get("path"): row.get("sha256")
            for row in protected
            if isinstance(row, dict)
        }
        if isinstance(protected, list)
        else {}
    )
    expected_protected = set(V24_NATIVE_PATHS) - {
        V24_MANIFEST_RELATIVE.as_posix()
    }
    if set(protected_map) != expected_protected:
        errors.append("v2.4 protected file path set differs")
    for relative in sorted(expected_protected):
        path = resolve_repo_file(root, relative)
        if path is None:
            errors.append(f"v2.4 protected file is missing: {relative}")
        elif protected_map.get(relative) != sha256_file(path):
            errors.append(f"v2.4 protected file SHA-256 differs: {relative}")

    package_root = root / V24_MANIFEST_RELATIVE.parent
    physical_paths = (
        {
            path.relative_to(root).as_posix()
            for path in package_root.rglob("*")
            if path.is_file()
        }
        if package_root.is_dir()
        else set()
    )
    dynamic_v24_paths = {
        relative
        for relative in expected_goal_paths(state)
        if relative.startswith(
            V24_MANIFEST_RELATIVE.parent.as_posix() + "/"
        )
    }
    expected_physical = set(V24_NATIVE_PATHS) | dynamic_v24_paths
    if physical_paths != expected_physical:
        errors.append("v2.4 package physical path set differs")

    goal_paths = list(expected_goal_paths(state))
    managed_paths = sorted(set(V24_NATIVE_PATHS) | set(goal_paths))
    _require_equal(
        errors,
        "v2.4 managed Goal paths",
        state.get("managed_goal_paths"),
        managed_paths,
    )
    _require_equal(
        errors,
        "v2.4 managed Goal path count",
        state.get("managed_goal_path_count"),
        len(managed_paths),
    )
    _require_equal(
        errors,
        "v2.4 Goal document paths",
        state.get("goal_document_paths"),
        goal_paths,
    )
    _require_equal(
        errors,
        "v2.4 Goal document count",
        state.get("goal_document_count"),
        len(goal_paths),
    )
    try:
        path_hash, content_hash = package_hashes(root, managed_paths)
    except (OSError, RuntimeError, ValueError) as exc:
        errors.append(f"v2.4 package hashes cannot be reproduced: {exc}")
    else:
        _require_equal(
            errors,
            "v2.4 package path-set SHA-256",
            state.get("path_set_sha256"),
            path_hash,
        )
        _require_equal(
            errors,
            "v2.4 package content-set SHA-256",
            state.get("content_set_sha256"),
            content_hash,
        )
    return errors


def validate_imported_goal_bindings(
    root: Path,
    *,
    state: dict[str, Any],
    archive: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    archived_state = archive.get("goal_execution")
    if not isinstance(archived_state, dict):
        return ["v2.3 archived goal_execution is missing"]
    statuses = archived_state.get("status_by_goal")
    imported = state.get("imported_predecessor_goal_bindings")
    if not isinstance(statuses, dict):
        return ["v2.3 archived status map is missing"]
    if not isinstance(imported, dict):
        return ["v2.4 imported predecessor Goal bindings are missing"]
    if set(imported) != set(statuses) or len(imported) != 20:
        return ["v2.4 imported predecessor Goal ID set differs"]
    source_paths = _goal_path_by_id(archive, {})
    source_records: dict[str, dict[str, Any]] = {}
    for field in (
        "imported_predecessor_goal_bindings",
        "dynamic_goal_inventory",
    ):
        records = archived_state.get(field)
        if isinstance(records, dict):
            for goal_id, record in records.items():
                if isinstance(goal_id, str) and isinstance(record, dict):
                    source_records.setdefault(goal_id, {}).update(record)
    archived_imported = archived_state.get("imported_predecessor_goal_bindings")
    archived_inventory = archived_state.get("dynamic_goal_inventory")
    completion_evidence = archived_state.get("completion_evidence_by_goal")
    completion_hashes: dict[str, str] = {}
    if isinstance(archived_imported, dict):
        for goal_id, record in archived_imported.items():
            digest = (
                record.get("completion_event_sha256")
                if isinstance(record, dict)
                else None
            )
            if isinstance(goal_id, str) and isinstance(digest, str):
                completion_hashes[goal_id] = digest
    history = archived_state.get("transition_history")
    if isinstance(history, list):
        for event in history:
            if (
                not isinstance(event, dict)
                or event.get("event_type")
                not in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}
                or not isinstance(event.get("event_sha256"), str)
            ):
                continue
            changes = event.get("status_changes")
            if not isinstance(changes, dict):
                continue
            for goal_id, status in changes.items():
                if isinstance(goal_id, str) and status == "COMPLETE_AT_TARGET":
                    completion_hashes[goal_id] = event["event_sha256"]
    for goal_id in sorted(imported):
        record = imported[goal_id]
        source = source_records.get(goal_id, {})
        if not isinstance(record, dict):
            errors.append(f"{goal_id}: imported Goal binding is malformed")
            continue
        path_value = source_paths.get(goal_id)
        path = resolve_repo_file(root, path_value)
        expected_record: dict[str, Any] = {
            "goal_id": goal_id,
            "path": path_value,
            "sha256": sha256_file(path) if path is not None else None,
            "goal_kind": source.get("goal_kind"),
            "status": statuses[goal_id],
            "source_package_id": V23_PACKAGE_ID,
        }
        dynamic = (
            archived_inventory.get(goal_id)
            if isinstance(archived_inventory, dict)
            else None
        )
        if isinstance(dynamic, dict):
            expected_record["materialized_event_sha256"] = dynamic.get(
                "materialized_event_sha256"
            )
        if statuses[goal_id] == "COMPLETE_AT_TARGET":
            expected_record["completion_event_sha256"] = completion_hashes.get(
                goal_id
            )
            expected_record["completion_evidence_refs"] = (
                completion_evidence.get(goal_id)
                if isinstance(completion_evidence, dict)
                else None
            )
        if set(record) != set(expected_record):
            errors.append(f"{goal_id}: imported Goal field set differs")
        for key, expected in expected_record.items():
            _require_equal(
                errors,
                f"{goal_id}: imported Goal {key}",
                record.get(key),
                expected,
            )
        if path is None:
            errors.append(f"{goal_id}: imported Goal path is missing")
    return errors


def validate_working_snapshot(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    """Validate a dynamic managed list, with an exact 498-path activation base."""
    errors: list[str] = []
    snapshot = checkpoint.get("working_tree_snapshot")
    state = checkpoint.get("goal_execution")
    if not isinstance(snapshot, dict):
        return ["v2.4 working_tree_snapshot is missing"]
    if not isinstance(state, dict):
        return ["v2.4 goal_execution is missing"]
    paths = snapshot.get("managed_changed_paths")
    if (
        not isinstance(paths, list)
        or not all(isinstance(path, str) for path in paths)
        or paths != sorted(set(paths))
    ):
        return ["v2.4 managed changed path list is malformed"]
    path_set = set(paths)
    required = set(EXPECTED_CONTROLLED_PATHS) | set(expected_goal_paths(state))
    if not required.issubset(path_set):
        errors.append(
            "v2.4 managed changed paths omit activation or dynamic Goal paths"
        )
    history = state.get("transition_history")
    event_count = len(history) if isinstance(history, list) else 0
    if event_count <= 2 and tuple(paths) != EXPECTED_CONTROLLED_PATHS:
        errors.append("v2.4 activation controlled path set differs")
    if V24_CHECKPOINT_RELATIVE.as_posix() in path_set:
        errors.append("v2.4 working snapshot includes its checkpoint")
    if any(
        path.startswith("docs/control/execution/goal-gates/")
        for path in paths
    ):
        errors.append("v2.4 working snapshot includes direct gate evidence")
    _require_equal(
        errors,
        "v2.4 managed changed path count",
        snapshot.get("managed_changed_path_count"),
        len(paths),
    )
    try:
        path_hash, content_hash = working_snapshot_hashes(root, paths)
    except (OSError, RuntimeError, ValueError) as exc:
        errors.append(f"v2.4 working snapshot cannot be reproduced: {exc}")
    else:
        _require_equal(
            errors,
            "v2.4 working snapshot path-set SHA-256",
            snapshot.get("path_set_sha256"),
            path_hash,
        )
        _require_equal(
            errors,
            "v2.4 working snapshot content-set SHA-256",
            snapshot.get("content_set_sha256"),
            content_hash,
        )
    return errors


def _require_equal(
    errors: list[str],
    label: str,
    actual: Any,
    expected: Any,
) -> None:
    if actual != expected:
        errors.append(f"{label} differs")


def validate_frozen_v23_boundary(
    root: Path,
    archive_path: Path = V23_ARCHIVE_RELATIVE,
) -> tuple[list[str], dict[str, Any]]:
    """Validate byte-exact v2.3 controls and the frozen active seq17 archive."""
    errors: list[str] = []
    for relative, expected in V23_FROZEN_FILE_SHA256.items():
        path = resolve_repo_file(root, relative)
        if path is None:
            errors.append(f"frozen v2.3 file is missing: {relative}")
        elif sha256_file(path) != expected:
            errors.append(f"frozen v2.3 file SHA-256 differs: {relative}")

    manifest = resolve_repo_file(root, V23_MANIFEST_RELATIVE)
    if manifest is None:
        errors.append("frozen v2.3 manifest is missing")
    elif sha256_file(manifest) != V23_MANIFEST_SHA256:
        errors.append("frozen v2.3 manifest SHA-256 differs")

    resolved_archive = resolve_repo_file(root, archive_path)
    if resolved_archive is None:
        return errors + ["frozen v2.3 archive is missing"], {}
    if sha256_file(resolved_archive) != V23_ARCHIVE_RAW_SHA256:
        errors.append("frozen v2.3 archive raw SHA-256 differs")
    try:
        archive = load_json(resolved_archive)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"frozen v2.3 archive cannot be loaded: {exc}"], {}

    state = archive.get("goal_execution")
    if not isinstance(state, dict):
        return errors + ["frozen v2.3 archive goal_execution is missing"], archive
    _require_equal(
        errors,
        "frozen v2.3 package ID",
        state.get("package_id"),
        V23_PACKAGE_ID,
    )
    _require_equal(
        errors,
        "frozen v2.3 plan version",
        state.get("static_plan_version"),
        V23_PLAN_VERSION,
    )
    _require_equal(
        errors,
        "frozen v2.3 manifest binding",
        state.get("static_plan_manifest_sha256"),
        V23_MANIFEST_SHA256,
    )
    history = state.get("transition_history")
    if not isinstance(history, list):
        errors.append("frozen v2.3 history is not a list")
        return errors, archive
    _require_equal(
        errors,
        "frozen v2.3 event count",
        len(history),
        V23_EVENT_COUNT,
    )
    tail = history[-1] if history and isinstance(history[-1], dict) else {}
    _require_equal(errors, "frozen v2.3 tail sequence", tail.get("sequence"), 17)
    _require_equal(
        errors,
        "frozen v2.3 tail type",
        tail.get("event_type"),
        "GOAL_READY",
    )
    _require_equal(
        errors,
        "frozen v2.3 tail subject",
        tail.get("subject_goal_id"),
        FP011_GOAL_ID,
    )
    _require_equal(
        errors,
        "frozen v2.3 tail SHA-256",
        tail.get("event_sha256"),
        V23_TAIL_SHA256,
    )
    _require_equal(
        errors,
        "frozen v2.3 history anchor",
        state.get("transition_history_anchor_sha256"),
        V23_TAIL_SHA256,
    )
    _require_equal(
        errors,
        "frozen v2.3 focus",
        state.get("focus_goal_id"),
        FP011_GOAL_ID,
    )
    statuses = state.get("status_by_goal")
    if not isinstance(statuses, dict):
        errors.append("frozen v2.3 status map is missing")
    else:
        _require_equal(
            errors,
            "frozen v2.3 FP011 status",
            statuses.get(FP011_GOAL_ID),
            "READY",
        )
        _require_equal(
            errors,
            "frozen v2.3 Goal count",
            len(statuses),
            20,
        )
    return errors, archive


def _load_direct_binding(
    root: Path,
    binding: Any,
    *,
    label: str,
) -> tuple[list[str], dict[str, Any]]:
    errors: list[str] = []
    if not isinstance(binding, dict):
        return [f"{label} binding is missing"], {}
    if set(binding) != {"document_id", "path", "file_sha256"}:
        errors.append(f"{label} binding field set differs")
    path = resolve_repo_file(root, binding.get("path"))
    digest = binding.get("file_sha256")
    if path is None:
        return errors + [f"{label} path is missing or unsafe"], {}
    if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
        errors.append(f"{label} SHA-256 is invalid")
    elif sha256_file(path) != digest:
        errors.append(f"{label} SHA-256 differs")
    try:
        payload = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"{label} cannot be loaded: {exc}"], {}
    if payload.get("document_id") != binding.get("document_id"):
        errors.append(f"{label} document ID differs")
    return errors, payload


def _fp008_private_file_identity(
    metadata: os.stat_result,
) -> tuple[int, int, int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def _open_fp008_gate_event_directory(
    root: Path,
    event_id: str,
    *,
    label: str,
    expected_identity: tuple[int, int] | None = None,
) -> tuple[list[str], int | None, tuple[int, int] | None]:
    errors: list[str] = []
    event_part = Path(event_id)
    if (
        not event_id
        or event_part.is_absolute()
        or event_part.parts != (event_id,)
        or event_id in {".", ".."}
    ):
        return [f"{label} event directory path is unsafe"], None, None
    try:
        resolved_root = root.resolve(strict=True)
    except (OSError, RuntimeError) as exc:
        return [f"{label} repository root cannot be opened: {exc}"], None, None
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    current_fd: int | None = None
    try:
        current_fd = os.open(resolved_root, directory_flags)
        for part in (*FP008_GATE_ROOT_RELATIVE.parts, event_id):
            try:
                before = os.stat(
                    part,
                    dir_fd=current_fd,
                    follow_symlinks=False,
                )
            except OSError as exc:
                errors.append(
                    f"{label} event directory component is missing: "
                    f"{part}: {exc}"
                )
                return errors, None, None
            if stat.S_ISLNK(before.st_mode):
                errors.append(
                    f"{label} event directory path contains a symlink: {part}"
                )
                return errors, None, None
            if not stat.S_ISDIR(before.st_mode):
                errors.append(
                    f"{label} event directory component is not a real "
                    f"directory: {part}"
                )
                return errors, None, None
            try:
                child_fd = os.open(part, directory_flags, dir_fd=current_fd)
            except OSError as exc:
                errors.append(
                    f"{label} event directory component cannot be opened: "
                    f"{part}: {exc}"
                )
                return errors, None, None
            opened = os.fstat(child_fd)
            if (opened.st_dev, opened.st_ino) != (
                before.st_dev,
                before.st_ino,
            ):
                os.close(child_fd)
                errors.append(
                    f"{label} event directory pathname changed while opening"
                )
                return errors, None, None
            os.close(current_fd)
            current_fd = child_fd

        metadata = os.fstat(current_fd)
        identity = (metadata.st_dev, metadata.st_ino)
        if not stat.S_ISDIR(metadata.st_mode):
            errors.append(f"{label} event directory is not a real directory")
        if stat.S_IMODE(metadata.st_mode) != FP008_PRIVATE_EVENT_DIRECTORY_MODE:
            errors.append(f"{label} event directory mode differs from 0700")
        if metadata.st_uid != os.geteuid():
            errors.append(f"{label} event directory owner differs")
        if expected_identity is not None and identity != expected_identity:
            errors.append(f"{label} event directory pathname identity differs")
        if errors:
            return errors, None, None
        result_fd = current_fd
        current_fd = None
        return [], result_fd, identity
    except OSError as exc:
        return [f"{label} event directory cannot be opened: {exc}"], None, None
    finally:
        if current_fd is not None:
            os.close(current_fd)


def _read_fp008_private_event_file(
    root: Path,
    *,
    event_id: str,
    relative_path: str,
    label: str,
    expected_directory_identity: tuple[int, int] | None = None,
    expected_file_identity: (
        tuple[int, int, int, int, int, int, int, int] | None
    ) = None,
) -> tuple[
    list[str],
    bytes | None,
    tuple[int, int, int, int, int, int, int, int] | None,
    tuple[int, int] | None,
]:
    relative = Path(relative_path)
    expected_parent = FP008_GATE_ROOT_RELATIVE / event_id
    if (
        relative.is_absolute()
        or relative.as_posix() != relative_path
        or relative.parent != expected_parent
        or relative.name in {"", ".", ".."}
    ):
        return [f"{label} path is not the exact event-scoped path"], None, None, None

    errors, directory_fd, directory_identity = (
        _open_fp008_gate_event_directory(
            root,
            event_id,
            label=label,
            expected_identity=expected_directory_identity,
        )
    )
    if directory_fd is None or directory_identity is None:
        return errors, None, None, None
    file_fd: int | None = None
    try:
        try:
            before = os.stat(
                relative.name,
                dir_fd=directory_fd,
                follow_symlinks=False,
            )
        except OSError as exc:
            return errors + [f"{label} file is missing: {exc}"], None, None, None
        if stat.S_ISLNK(before.st_mode):
            return errors + [f"{label} path contains a symlink"], None, None, None
        if not stat.S_ISREG(before.st_mode):
            return errors + [f"{label} is not a regular file"], None, None, None
        if stat.S_IMODE(before.st_mode) != FP008_PRIVATE_EVIDENCE_FILE_MODE:
            errors.append(f"{label} mode differs from 0600")
        if before.st_uid != os.geteuid():
            errors.append(f"{label} owner differs")
        if before.st_nlink != 1:
            errors.append(f"{label} link count differs from 1")
        if before.st_size > FP008_PRIVATE_EVIDENCE_MAX_BYTES:
            errors.append(f"{label} exceeds the private evidence size limit")
        before_identity = _fp008_private_file_identity(before)
        if expected_file_identity is not None:
            if before_identity[:2] != expected_file_identity[:2]:
                errors.append(f"{label} pathname identity differs")
            elif before_identity != expected_file_identity:
                errors.append(f"{label} metadata identity differs")
        if errors:
            return errors, None, None, directory_identity

        file_flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        try:
            file_fd = os.open(relative.name, file_flags, dir_fd=directory_fd)
        except OSError as exc:
            return [f"{label} cannot be opened safely: {exc}"], None, None, None
        opened = os.fstat(file_fd)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            return [f"{label} pathname changed while opening"], None, None, None
        if _fp008_private_file_identity(opened) != before_identity:
            return [f"{label} metadata changed while opening"], None, None, None

        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(file_fd, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
            total += len(chunk)
            if total > FP008_PRIVATE_EVIDENCE_MAX_BYTES:
                return [f"{label} exceeds the private evidence size limit"], None, None, None
        opened_after = os.fstat(file_fd)
        path_after = os.stat(
            relative.name,
            dir_fd=directory_fd,
            follow_symlinks=False,
        )
        if (path_after.st_dev, path_after.st_ino) != before_identity[:2]:
            return [f"{label} pathname changed while reading"], None, None, None
        if (
            _fp008_private_file_identity(opened_after) != before_identity
            or _fp008_private_file_identity(path_after) != before_identity
        ):
            return [f"{label} metadata changed while reading"], None, None, None
        content = b"".join(chunks)
    except OSError as exc:
        return [f"{label} cannot be read safely: {exc}"], None, None, None
    finally:
        if file_fd is not None:
            os.close(file_fd)
        os.close(directory_fd)

    final_errors, final_directory_fd, final_identity = (
        _open_fp008_gate_event_directory(
            root,
            event_id,
            label=label,
            expected_identity=directory_identity,
        )
    )
    if final_directory_fd is not None:
        os.close(final_directory_fd)
    if final_errors or final_identity is None:
        return errors + final_errors, None, None, None
    return errors, content, before_identity, directory_identity


def _load_fp008_private_binding(
    root: Path,
    binding: Any,
    *,
    event_id: str,
    expected_path: str,
    label: str,
) -> tuple[
    list[str],
    dict[str, Any],
    tuple[int, int, int, int, int, int, int, int] | None,
    tuple[int, int] | None,
]:
    errors: list[str] = []
    if not isinstance(binding, dict):
        return [f"{label} binding is missing"], {}, None, None
    if set(binding) != {"document_id", "path", "file_sha256"}:
        errors.append(f"{label} binding field set differs")
    _require_equal(
        errors,
        f"{label} exact event-scoped path",
        binding.get("path"),
        expected_path,
    )
    digest = binding.get("file_sha256")
    if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
        errors.append(f"{label} SHA-256 is invalid")
    read_errors, content, file_identity, directory_identity = (
        _read_fp008_private_event_file(
            root,
            event_id=event_id,
            relative_path=expected_path,
            label=label,
        )
    )
    errors.extend(read_errors)
    if content is None:
        return errors, {}, file_identity, directory_identity
    if isinstance(digest, str) and SHA256_RE.fullmatch(digest):
        if sha256_bytes(content) != digest:
            errors.append(f"{label} SHA-256 differs")
    try:
        payload = json.loads(content)
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"{label} cannot be loaded: {exc}"], {}, file_identity, directory_identity
    if not isinstance(payload, dict):
        errors.append(f"{label} JSON root is not an object")
        return errors, {}, file_identity, directory_identity
    if payload.get("document_id") != binding.get("document_id"):
        errors.append(f"{label} document ID differs")
    return errors, payload, file_identity, directory_identity


def _validate_fp008_gate_event_inventory(
    root: Path,
    *,
    event_id: str,
    expected_checks: list[dict[str, str]],
    expected_directory_identity: tuple[int, int],
    label: str,
    receipt_name: str = "implementation-start-gate-receipt.json",
) -> list[str]:
    errors: list[str] = []
    check_ids = [item.get("check_id") for item in expected_checks]
    if check_ids not in (
        FP008_START_GATE_CHECK_IDS,
        FP046_START_GATE_CHECK_IDS,
    ):
        errors.append(f"{label} ordered log inventory contract differs")
        return errors
    expected_entries = {
        receipt_name,
        *(
            f"{index:02d}-{check_id}.log"
            for index, check_id in enumerate(check_ids, start=1)
        ),
    }
    directory_errors, directory_fd, _ = _open_fp008_gate_event_directory(
        root,
        event_id,
        label=label,
        expected_identity=expected_directory_identity,
    )
    errors.extend(directory_errors)
    if directory_fd is None:
        return errors
    try:
        observed_entries = set(os.listdir(directory_fd))
    except OSError as exc:
        errors.append(f"{label} inventory cannot be read: {exc}")
        observed_entries = set()
    finally:
        os.close(directory_fd)
    if observed_entries != expected_entries:
        errors.append(
            f"{label} inventory must contain only the 9 ordered logs and receipt"
        )
    final_errors, final_directory_fd, _ = _open_fp008_gate_event_directory(
        root,
        event_id,
        label=label,
        expected_identity=expected_directory_identity,
    )
    errors.extend(final_errors)
    if final_directory_fd is not None:
        os.close(final_directory_fd)
    return errors


def _manifest_transition_contract(manifest: dict[str, Any]) -> dict[str, Any]:
    value = manifest.get("transition_contract")
    return value if isinstance(value, dict) else {}


def _parse_iso_datetime(
    errors: list[str],
    label: str,
    value: Any,
) -> datetime | None:
    if not isinstance(value, str):
        errors.append(f"{label} is missing")
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        errors.append(f"{label} is not ISO-8601")
        return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        errors.append(f"{label} lacks a timezone")
        return None
    if parsed.microsecond:
        errors.append(f"{label} must use second precision")
        return None
    return parsed


def _control_checks(
    manifest: dict[str, Any],
    field: str,
) -> list[dict[str, str]]:
    control = manifest.get("successor_control_contract")
    checks = control.get(field) if isinstance(control, dict) else None
    if not isinstance(checks, list) or not all(
        isinstance(item, dict)
        and set(item) == {"check_id", "command"}
        and isinstance(item.get("check_id"), str)
        and isinstance(item.get("command"), str)
        for item in checks
    ):
        return []
    return [
        {"check_id": item["check_id"], "command": item["command"]}
        for item in checks
    ]


def _check_contract_sha256(
    version: Any,
    checks: list[dict[str, str]],
) -> str:
    return canonical_json_sha256(
        {
            "contract_version": version,
            "checks": checks,
        }
    )


INITIAL_START_GATE_LABEL = "v2.4 initial_start gate"
SILENT_SUCCESS_CHECK_ID = "TEST_LAYER_REGISTRY_VALIDATE"
SILENT_SUCCESS_COMMAND = (
    'WALKSAFE_LOCKED_TEST_PYTHON="${WALKSAFE_LOCKED_TEST_PYTHON:-'
    "/home/ddobagi/.local/share/hanium-dreamup/"
    'walksafe-general-cpu-verify-20260715/bin/python}" && '
    'test -x "${WALKSAFE_LOCKED_TEST_PYTHON}" && '
    'PYTHON_BIN="${WALKSAFE_LOCKED_TEST_PYTHON}" '
    "bash scripts/run_walksafe_test_layers_20260711.sh validate"
)
EMPTY_OUTPUT_SHA256 = (
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
)
CHECK_RUN_EXECUTED_AT_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}"
    r"(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})\Z"
)


def _parse_check_run_executed_at(
    errors: list[str],
    label: str,
    value: Any,
) -> datetime | None:
    if not isinstance(value, str) or not CHECK_RUN_EXECUTED_AT_RE.fullmatch(value):
        errors.append(
            f"{label} must use timezone-aware ISO-8601 with "
            "0 to 6 fractional digits"
        )
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        errors.append(f"{label} is not a valid ISO-8601 timestamp")
        return None
    if parsed.utcoffset() is None:
        errors.append(f"{label} must include a timezone")
        return None
    return parsed


def _is_initial_start_silent_success(
    *,
    label: str,
    event_id: str,
    index: int,
    check_id: str,
    expected_command: str,
    expected_output: str,
    run: dict[str, Any],
    output_byte_count: int,
    output_sha256: Any,
) -> bool:
    exact_output = (
        f"docs/control/execution/goal-gates/{event_id}/"
        "15-TEST_LAYER_REGISTRY_VALIDATE.log"
    )
    return (
        label == INITIAL_START_GATE_LABEL
        and index == 15
        and check_id == SILENT_SUCCESS_CHECK_ID
        and run.get("check_id") == SILENT_SUCCESS_CHECK_ID
        and expected_command == SILENT_SUCCESS_COMMAND
        and run.get("command") == SILENT_SUCCESS_COMMAND
        and expected_output == exact_output
        and run.get("output_path") == exact_output
        and run.get("exit_code") == 0
        and output_byte_count == 0
        and output_sha256 == EMPTY_OUTPUT_SHA256
    )


def _validate_check_runs(
    root: Path,
    *,
    label: str,
    event_id: str,
    receipt: dict[str, Any],
    expected_checks: list[dict[str, str]],
    fp008_private_evidence: bool = False,
    fp008_event_directory_identity: tuple[int, int] | None = None,
    fp008_receipt_name: str = "implementation-start-gate-receipt.json",
    fp008_retained_contents: Mapping[str, bytes] | None = None,
) -> tuple[list[str], dict[str, Any] | None]:
    errors: list[str] = []
    window = receipt.get("execution_window")
    if not isinstance(window, dict) or set(window) != {
        "started_at",
        "ended_at",
    }:
        errors.append(f"{label} execution window differs")
        window = {}
    started_at = _parse_iso_datetime(
        errors,
        f"{label} started_at",
        window.get("started_at"),
    )
    ended_at = _parse_iso_datetime(
        errors,
        f"{label} ended_at",
        window.get("ended_at"),
    )
    generated_at = _parse_iso_datetime(
        errors,
        f"{label} generated_at",
        receipt.get("generated_at"),
    )
    if (
        started_at is not None
        and ended_at is not None
        and generated_at is not None
        and not (started_at <= ended_at <= generated_at)
    ):
        errors.append(f"{label} execution chronology differs")

    runs = receipt.get("check_runs")
    if not isinstance(runs, list) or len(runs) != len(expected_checks):
        errors.append(
            f"{label} check run count differs: "
            f"expected {len(expected_checks)}"
        )
        return errors, None
    private_directory_identity = fp008_event_directory_identity
    private_outputs: list[
        tuple[
            str,
            Any,
            tuple[int, int, int, int, int, int, int, int],
        ]
    ] = []
    if fp008_private_evidence:
        if fp008_retained_contents is not None:
            expected_retained_paths = {
                f"docs/control/execution/goal-gates/{event_id}/"
                f"{index:02d}-{item['check_id']}.log"
                for index, item in enumerate(expected_checks, start=1)
            } | {
                f"docs/control/execution/goal-gates/{event_id}/"
                f"{fp008_receipt_name}"
            }
            if (
                private_directory_identity is None
                or set(fp008_retained_contents) != expected_retained_paths
                or not all(
                    isinstance(content, bytes)
                    for content in fp008_retained_contents.values()
                )
            ):
                errors.append(f"{label} retained private evidence differs")
                return errors, None
        else:
            directory_errors, directory_fd, observed_identity = (
                _open_fp008_gate_event_directory(
                    root,
                    event_id,
                    label=label,
                    expected_identity=private_directory_identity,
                )
            )
            errors.extend(directory_errors)
            if directory_fd is not None:
                os.close(directory_fd)
            if observed_identity is None:
                return errors, None
            private_directory_identity = observed_identity
            errors.extend(
                _validate_fp008_gate_event_inventory(
                    root,
                    event_id=event_id,
                    expected_checks=expected_checks,
                    expected_directory_identity=private_directory_identity,
                    label=label,
                    receipt_name=fp008_receipt_name,
                )
            )
    previous_executed_at: datetime | None = None
    repository_payload: dict[str, Any] | None = None
    observed_paths: set[str] = set()
    for index, (run, expected) in enumerate(
        zip(runs, expected_checks, strict=True),
        start=1,
    ):
        run_label = f"{label} check {index}"
        if not isinstance(run, dict):
            errors.append(f"{run_label} is not an object")
            continue
        if set(run) != {
            "check_id",
            "command",
            "executed_at",
            "exit_code",
            "output_path",
            "output_sha256",
        }:
            errors.append(f"{run_label} field set differs")
        check_id = expected["check_id"]
        _require_equal(
            errors,
            f"{run_label} ID",
            run.get("check_id"),
            check_id,
        )
        _require_equal(
            errors,
            f"{run_label} command",
            run.get("command"),
            expected["command"],
        )
        _require_equal(
            errors,
            f"{run_label} exit code",
            run.get("exit_code"),
            0,
        )
        expected_output = (
            f"docs/control/execution/goal-gates/{event_id}/"
            f"{index:02d}-{check_id}.log"
        )
        output_value = run.get("output_path")
        _require_equal(
            errors,
            f"{run_label} output path",
            output_value,
            expected_output,
        )
        if isinstance(output_value, str):
            if output_value in observed_paths:
                errors.append(f"{run_label} reuses an output path")
            observed_paths.add(output_value)
        executed_at = _parse_check_run_executed_at(
            errors,
            f"{run_label} executed_at",
            run.get("executed_at"),
        )
        if (
            executed_at is not None
            and started_at is not None
            and ended_at is not None
            and not (started_at <= executed_at <= ended_at)
        ):
            errors.append(f"{run_label} is outside the execution window")
        if (
            executed_at is not None
            and previous_executed_at is not None
            and executed_at <= previous_executed_at
        ):
            errors.append(f"{run_label} time is not strictly increasing")
        if executed_at is not None:
            previous_executed_at = executed_at

        digest = run.get("output_sha256")
        output_path: Path | None = None
        output_content: bytes | None = None
        if fp008_private_evidence and isinstance(output_value, str):
            if fp008_retained_contents is not None:
                output_content = fp008_retained_contents.get(expected_output)
                if output_content is None:
                    errors.append(f"{run_label} retained output is missing")
                    continue
            else:
                (
                    private_errors,
                    output_content,
                    output_identity,
                    _,
                ) = _read_fp008_private_event_file(
                    root,
                    event_id=event_id,
                    relative_path=expected_output,
                    label=f"{run_label} output",
                    expected_directory_identity=private_directory_identity,
                )
                errors.extend(private_errors)
                if output_content is None or output_identity is None:
                    continue
                private_outputs.append(
                    (expected_output, digest, output_identity)
                )
            output_byte_count = len(output_content)
        else:
            output_path = resolve_repo_file(root, output_value)
            if output_path is None:
                errors.append(f"{run_label} output is missing or unsafe")
                continue
            output_byte_count = output_path.stat().st_size
        silent_success = _is_initial_start_silent_success(
            label=label,
            event_id=event_id,
            index=index,
            check_id=check_id,
            expected_command=expected["command"],
            expected_output=expected_output,
            run=run,
            output_byte_count=output_byte_count,
            output_sha256=digest,
        )
        if output_byte_count <= 0 and not silent_success:
            errors.append(f"{run_label} output is empty")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            errors.append(f"{run_label} output SHA-256 is invalid")
        elif (
            sha256_bytes(output_content)
            if output_content is not None
            else sha256_file(output_path)
        ) != digest:
            errors.append(f"{run_label} output SHA-256 differs")
        if check_id == "REPOSITORY_STATE":
            try:
                if output_content is not None:
                    loaded_payload = json.loads(output_content)
                    if not isinstance(loaded_payload, dict):
                        raise ValueError("JSON root is not an object")
                    repository_payload = loaded_payload
                else:
                    repository_payload = load_json(output_path)
            except (
                OSError,
                UnicodeDecodeError,
                ValueError,
                json.JSONDecodeError,
            ) as exc:
                errors.append(
                    f"{run_label} repository payload cannot be loaded: {exc}"
                )
    if (
        fp008_private_evidence
        and private_directory_identity is not None
        and fp008_retained_contents is None
    ):
        for output_value, digest, output_identity in private_outputs:
            final_errors, final_content, _, _ = (
                _read_fp008_private_event_file(
                    root,
                    event_id=event_id,
                    relative_path=output_value,
                    label=f"{label} final output",
                    expected_directory_identity=private_directory_identity,
                    expected_file_identity=output_identity,
                )
            )
            errors.extend(final_errors)
            if (
                final_content is not None
                and isinstance(digest, str)
                and SHA256_RE.fullmatch(digest)
                and sha256_bytes(final_content) != digest
            ):
                errors.append(f"{label} output changed during validation")
        errors.extend(
            _validate_fp008_gate_event_inventory(
                root,
                event_id=event_id,
                expected_checks=expected_checks,
                expected_directory_identity=private_directory_identity,
                label=f"{label} final",
                receipt_name=fp008_receipt_name,
            )
        )
    return errors, repository_payload


def _validate_repository_payload(
    *,
    label: str,
    event_id: str,
    payload: dict[str, Any] | None,
    snapshot: Any,
) -> list[str]:
    errors: list[str] = []
    if payload is None:
        return [f"{label} repository-state payload is missing"]
    if not isinstance(snapshot, dict):
        return [f"{label} repository snapshot is missing"]
    _require_equal(
        errors,
        f"{label} repository evidence type",
        payload.get("evidence_type"),
        _v23_utility.GATE_REPOSITORY_STATE_EVIDENCE_TYPE,
    )
    _require_equal(
        errors,
        f"{label} repository event ID",
        payload.get("gate_event_id"),
        event_id,
    )
    repository = payload.get("repository")
    status = payload.get("git_status_raw")
    dirty = payload.get("dirty_snapshot")
    controlled = payload.get("checkpoint_controlled_working_snapshot")
    for field, source, key in (
        ("head commit", repository, "head_commit"),
        ("branch", repository, "branch"),
        ("object format", repository, "object_format"),
        ("Git status SHA-256", status, "sha256"),
        ("Git status byte count", status, "byte_count"),
        ("Git status record count", status, "record_count"),
        ("dirty path count", dirty, "dirty_path_count"),
        ("dirty path-set SHA-256", dirty, "path_set_sha256"),
        ("dirty content-set SHA-256", dirty, "content_set_sha256"),
        ("index-state SHA-256", dirty, "index_state_sha256"),
        (
            "checkpoint base HEAD",
            controlled,
            "base_head",
        ),
        (
            "checkpoint path count",
            controlled,
            "managed_changed_path_count",
        ),
        (
            "checkpoint path-set SHA-256",
            controlled,
            "path_set_sha256",
        ),
        (
            "checkpoint content-set SHA-256",
            controlled,
            "content_set_sha256",
        ),
    ):
        if not isinstance(source, dict):
            errors.append(f"{label} {field} source is missing")
            continue
        snapshot_key = {
            "head_commit": "head_commit",
            "branch": "branch",
            "object_format": "object_format",
            "sha256": "git_status_raw_sha256",
            "byte_count": "git_status_raw_byte_count",
            "record_count": "git_status_raw_record_count",
            "dirty_path_count": "dirty_path_count",
            "path_set_sha256": (
                "checkpoint_path_set_sha256"
                if source is controlled
                else "path_set_sha256"
            ),
            "content_set_sha256": (
                "checkpoint_content_set_sha256"
                if source is controlled
                else "content_set_sha256"
            ),
            "index_state_sha256": "index_state_sha256",
            "base_head": "checkpoint_base_head",
            "managed_changed_path_count": "checkpoint_managed_path_count",
        }[key]
        _require_equal(
            errors,
            f"{label} {field}",
            source.get(key),
            snapshot.get(snapshot_key),
        )
    return errors


def _goal_path_by_id(
    archive: dict[str, Any],
    checkpoint: dict[str, Any],
) -> dict[str, str]:
    result: dict[str, str] = {}
    for source in (archive, checkpoint):
        state = source.get("goal_execution")
        if not isinstance(state, dict):
            continue
        for field in (
            "imported_predecessor_goal_bindings",
            "dynamic_goal_inventory",
        ):
            records = state.get(field)
            if not isinstance(records, dict):
                continue
            for goal_id, record in records.items():
                path = record.get("path") if isinstance(record, dict) else None
                if isinstance(goal_id, str) and isinstance(path, str):
                    result[goal_id] = path
    return result


def _checkpoint_repository_snapshot(
    checkpoint: dict[str, Any],
) -> dict[str, Any]:
    working = checkpoint.get("working_tree_snapshot")
    repository = checkpoint.get("repository")
    if not isinstance(working, dict) or not isinstance(repository, dict):
        return {}
    return {
        "base_head": working.get("base_head"),
        "branch": repository.get("branch"),
        "managed_path_count": working.get("managed_changed_path_count"),
        "path_set_sha256": working.get("path_set_sha256"),
        "content_set_sha256": working.get("content_set_sha256"),
    }


def _validate_activation_receipts(
    root: Path,
    *,
    event: dict[str, Any],
    prepared: dict[str, Any],
    checkpoint: dict[str, Any],
    activation_is_tail: bool,
    manifest: dict[str, Any],
    manifest_sha256: str,
    expected_authorization_sha256: str | None,
) -> list[str]:
    errors: list[str] = []
    authorization_generated_at: datetime | None = None
    event_id = event.get("event_id")
    if not isinstance(event_id, str):
        return ["v2.4 activation event ID is missing"]
    event_time = _parse_iso_datetime(
        errors,
        "v2.4 activation event occurred_at",
        event.get("occurred_at"),
    )
    auth_binding = event.get("package_activation_authorization_binding")
    expected_auth_path = (
        f"docs/control/execution/goal-gates/{event_id}/authorization.json"
    )
    if not isinstance(auth_binding, dict):
        errors.append("v2.4 activation authorization binding is missing")
    else:
        _require_equal(
            errors,
            "v2.4 activation authorization path",
            auth_binding.get("path"),
            expected_auth_path,
        )
    auth_errors, authorization = _load_direct_binding(
        root,
        auth_binding,
        label="v2.4 activation authorization",
    )
    errors.extend(auth_errors)
    if expected_authorization_sha256 is None:
        errors.append("v2.4 activation authorization trust anchor is not finalized")
    elif (
        isinstance(auth_binding, dict)
        and auth_binding.get("file_sha256") != expected_authorization_sha256
    ):
        errors.append("v2.4 activation authorization trust anchor differs")
    if authorization:
        required_fields = {
            "schema_version",
            "document_id",
            "evidence_type",
            "status",
            "package_id",
            "activation_request",
            "authorization_scope",
            "timestamp_basis",
            "authorized_at",
            "generated_at",
            "user_response",
        }
        if set(authorization) != required_fields:
            errors.append("v2.4 authorization field set differs")
        for label, actual, expected in (
            ("schema version", authorization.get("schema_version"), "1.0"),
            (
                "evidence type",
                authorization.get("evidence_type"),
                "PACKAGE_ACTIVATION_AUTHORIZATION",
            ),
            ("status", authorization.get("status"), "AUTHORIZED"),
            ("package", authorization.get("package_id"), V24_PACKAGE_ID),
            (
                "timestamp basis",
                authorization.get("timestamp_basis"),
                "LOCAL_SESSION_PROCESSING_TIME_AFTER_USER_RESPONSE",
            ),
        ):
            _require_equal(
                errors,
                f"v2.4 authorization {label}",
                actual,
                expected,
            )
        request = authorization.get("activation_request")
        scope = authorization.get("authorization_scope")
        response = authorization.get("user_response")
        if not isinstance(request, dict) or set(request) != {
            "request_id",
            "source_kind",
            "request_sha256",
            "requested_at",
        }:
            errors.append("v2.4 authorization request differs")
            request = {}
        if request.get("source_kind") != "USER_EXPLICIT_REQUEST":
            errors.append("v2.4 authorization source kind differs")
        if not isinstance(scope, dict):
            errors.append("v2.4 authorization scope is missing")
        else:
            if set(scope) != {
                "static_plan_manifest_sha256",
                "initial_event_sha256",
                "focus_goal_id",
                "permitted_transitions",
                "continuous_internal_execution",
            }:
                errors.append("v2.4 authorization scope field set differs")
            _require_equal(
                errors,
                "v2.4 authorization manifest scope",
                scope.get("static_plan_manifest_sha256"),
                manifest_sha256,
            )
            _require_equal(
                errors,
                "v2.4 authorization prepared-event scope",
                scope.get("initial_event_sha256"),
                prepared.get("event_sha256"),
            )
            _require_equal(
                errors,
                "v2.4 authorization focus scope",
                scope.get("focus_goal_id"),
                FP011_GOAL_ID,
            )
            _require_equal(
                errors,
                "v2.4 authorization transition scope",
                scope.get("permitted_transitions"),
                [
                    "PACKAGE_ACTIVATED_AFTER_QUICK_GATE_PASS",
                    "GOAL_STARTED_AFTER_FULL_START_GATE_PASS",
                ],
            )
            _require_equal(
                errors,
                "v2.4 authorization continuous execution scope",
                scope.get("continuous_internal_execution"),
                "DEPENDENCY_DAG_UNTIL_REAL_POLICY_OR_EXTERNAL_BLOCKER",
            )
        if not isinstance(response, dict) or set(response) != {
            "literal_utf8",
            "canonicalization",
            "sha256",
        }:
            errors.append("v2.4 authorization user response differs")
            response = {}
        literal = response.get("literal_utf8")
        literal_sha256 = (
            hashlib.sha256(literal.encode("utf-8")).hexdigest()
            if isinstance(literal, str)
            else None
        )
        _require_equal(
            errors,
            "v2.4 authorization literal",
            literal,
            EXPECTED_V24_ACTIVATION_AUTHORIZATION_LITERAL,
        )
        _require_equal(
            errors,
            "v2.4 authorization canonicalization",
            response.get("canonicalization"),
            "UTF-8_WITHOUT_TRAILING_NEWLINE",
        )
        _require_equal(
            errors,
            "v2.4 authorization response SHA-256",
            response.get("sha256"),
            literal_sha256,
        )
        _require_equal(
            errors,
            "v2.4 authorization request SHA-256",
            request.get("request_sha256"),
            literal_sha256,
        )
        requested_at = _parse_iso_datetime(
            errors,
            "v2.4 authorization requested_at",
            request.get("requested_at"),
        )
        authorized_at = _parse_iso_datetime(
            errors,
            "v2.4 authorization authorized_at",
            authorization.get("authorized_at"),
        )
        authorization_generated_at = _parse_iso_datetime(
            errors,
            "v2.4 authorization generated_at",
            authorization.get("generated_at"),
        )
        if (
            None not in {
                requested_at,
                authorized_at,
                authorization_generated_at,
                event_time,
            }
            and not (
                requested_at
                <= authorized_at
                <= authorization_generated_at
                <= event_time
            )
        ):
            errors.append("v2.4 authorization chronology differs")

    quick_binding = event.get("activation_quick_gate_binding")
    expected_quick_path = (
        f"docs/control/execution/goal-gates/{event_id}/"
        "quick-gate-receipt.json"
    )
    if not isinstance(quick_binding, dict):
        errors.append("v2.4 activation quick gate binding is missing")
    else:
        _require_equal(
            errors,
            "v2.4 activation quick gate path",
            quick_binding.get("path"),
            expected_quick_path,
        )
    quick_errors, quick = _load_direct_binding(
        root,
        quick_binding,
        label="v2.4 activation quick gate",
    )
    errors.extend(quick_errors)
    if quick:
        if set(quick) != V24_QUICK_GATE_RECEIPT_FIELDS:
            errors.append("v2.4 quick gate receipt field set differs")
        transition = _manifest_transition_contract(manifest)
        checks = _control_checks(manifest, "quick_activation_checks")
        contract_version = transition.get("check_command_contract_version")
        contract_sha256 = _check_contract_sha256(
            contract_version,
            checks,
        )
        expected_ids = [item["check_id"] for item in checks]
        for label, actual, expected in (
            ("schema version", quick.get("schema_version"), "1.0"),
            (
                "evidence type",
                quick.get("evidence_type"),
                "PACKAGE_ACTIVATION_QUICK_GATE",
            ),
            ("status", quick.get("status"), "PASS"),
            ("package", quick.get("package_id"), V24_PACKAGE_ID),
            (
                "target event",
                quick.get("target_transition_event_id"),
                event.get("event_id"),
            ),
            (
                "manifest",
                quick.get("static_plan_manifest_sha256"),
                manifest_sha256,
            ),
            (
                "contract version",
                quick.get("check_command_contract_version"),
                contract_version,
            ),
            (
                "contract SHA-256",
                quick.get("check_command_contract_sha256"),
                contract_sha256,
            ),
        ):
            _require_equal(errors, f"v2.4 quick gate {label}", actual, expected)
        _require_equal(
            errors,
            "v2.4 manifest quick gate IDs",
            transition.get("quick_activation_check_ids"),
            expected_ids,
        )
        _require_equal(
            errors,
            "v2.4 manifest quick gate contract SHA-256",
            transition.get("quick_activation_check_contract_sha256"),
            contract_sha256,
        )
        if isinstance(auth_binding, dict):
            _require_equal(
                errors,
                "v2.4 quick gate authorization binding",
                quick.get("authorization_receipt_binding"),
                auth_binding,
            )
        _require_equal(
            errors,
            "v2.4 quick gate receipt/event repository snapshot",
            quick.get("repository_snapshot"),
            event.get("repository_snapshot_before"),
        )
        if activation_is_tail:
            _require_equal(
                errors,
                "v2.4 quick gate checkpoint repository snapshot",
                quick.get("repository_snapshot"),
                _checkpoint_repository_snapshot(checkpoint),
            )
        run_errors, repository_payload = _validate_check_runs(
            root,
            label="v2.4 activation quick gate",
            event_id=event_id,
            receipt=quick,
            expected_checks=checks,
        )
        errors.extend(run_errors)
        if repository_payload is not None:
            errors.append(
                "v2.4 activation quick gate must not contain "
                "a repository-state check"
            )
        generated_at = _parse_iso_datetime(
            errors,
            "v2.4 quick gate generated_at",
            quick.get("generated_at"),
        )
        quick_window = quick.get("execution_window")
        quick_started_at = _parse_iso_datetime(
            errors,
            "v2.4 quick gate started_at cross-check",
            (
                quick_window.get("started_at")
                if isinstance(quick_window, dict)
                else None
            ),
        )
        if (
            authorization_generated_at is not None
            and quick_started_at is not None
            and authorization_generated_at > quick_started_at
        ):
            errors.append(
                "v2.4 authorization/quick gate chronology differs"
            )
        if (
            generated_at is not None
            and event_time is not None
            and not (
                generated_at <= event_time
                and event_time - generated_at <= timedelta(hours=1)
            )
        ):
            errors.append("v2.4 quick gate freshness differs")
    return errors


def _fp008_start_gate_contract(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
) -> tuple[
    list[str],
    list[dict[str, str]],
    dict[str, Any],
    dict[str, Any],
]:
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    event_sequence = event.get("sequence")
    ready_events = [
        item
        for item in history
        if isinstance(item, dict)
        and item.get("event_type") == "GOAL_READY"
        and item.get("subject_goal_id") == FP008_GOAL_ID
        and isinstance(item.get("sequence"), int)
        and isinstance(event_sequence, int)
        and item["sequence"] < event_sequence
    ] if isinstance(history, list) else []
    if len(ready_events) != 1:
        return ["FP008 start gate READY event is missing or ambiguous"], [], {}, {}
    ready = ready_events[0]
    expected_previous_event_sha256 = ready.get("event_sha256")
    if event.get("event_type") == "WORK_SESSION_RESUMED":
        execution_sessions = [
            item
            for item in history
            if isinstance(item, dict)
            and item.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and item.get("subject_goal_id") == FP008_GOAL_ID
            and isinstance(item.get("sequence"), int)
            and isinstance(event_sequence, int)
            and item["sequence"] < event_sequence
        ]
        previous_session = execution_sessions[-1] if execution_sessions else {}
        expected_previous_event_sha256 = previous_session.get("event_sha256")
        if (
            previous_session.get("event_type") not in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            or event.get("previous_execution_session_event_sha256")
            != expected_previous_event_sha256
        ):
            errors.append("FP008 resume execution-session lineage differs")
    if (
        ready.get("sequence") != 46
        or ready.get("event_id")
        != "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP008-20260803-001"
        or ready.get("event_sha256") != event_sha256(ready)
        or event.get("previous_event_sha256") != expected_previous_event_sha256
    ):
        errors.append("FP008 start gate READY event lineage differs")

    binding = ready.get("implementation_start_gate_contract_binding")
    if not isinstance(binding, dict) or set(binding) != FP008_START_GATE_CONTRACT_BINDING_FIELDS:
        return errors + ["FP008 start gate contract binding differs"], [], {}, ready
    for label, actual, expected in (
        ("schema version", binding.get("schema_version"), "1.0"),
        (
            "document ID",
            binding.get("document_id"),
            "WS-FP008-INITIAL-START-GATE-CONTRACT-20260803-001",
        ),
        ("path", binding.get("path"), FP008_START_GATE_CONTRACT_PATH),
        ("contract ID", binding.get("contract_id"), "WS-FP008-INTERNAL-START-GATE-R001"),
        ("contract version", binding.get("contract_version"), "2026-08-03.1"),
    ):
        _require_equal(errors, f"FP008 start gate contract {label}", actual, expected)
    if _contains_symlink(root, FP008_START_GATE_CONTRACT_PATH):
        errors.append("FP008 start gate contract path contains a symlink")
        return errors, [], binding, ready
    path = resolve_repo_file(root, binding.get("path"))
    if path is None:
        return errors + ["FP008 start gate contract path is missing or unsafe"], [], binding, ready
    file_sha256 = binding.get("file_sha256")
    if not isinstance(file_sha256, str) or not SHA256_RE.fullmatch(file_sha256):
        errors.append("FP008 start gate contract file SHA-256 is invalid")
    elif sha256_file(path) != file_sha256:
        errors.append("FP008 start gate contract file SHA-256 differs")
    try:
        contract_value = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"FP008 start gate contract cannot be loaded: {exc}"], [], binding, ready
    if set(contract_value) != FP008_START_GATE_CONTRACT_FIELDS:
        errors.append("FP008 start gate contract field set differs")
    canonical_sha256 = canonical_json_sha256(contract_value)
    _require_equal(
        errors,
        "FP008 start gate canonical contract SHA-256",
        binding.get("canonical_contract_sha256"),
        canonical_sha256,
    )
    for label, actual, expected in (
        ("schema version", contract_value.get("schema_version"), "1.0"),
        ("document ID", contract_value.get("document_id"), binding.get("document_id")),
        ("contract ID", contract_value.get("contract_id"), binding.get("contract_id")),
        ("contract version", contract_value.get("contract_version"), binding.get("contract_version")),
        ("target Goal", contract_value.get("target_goal_id"), FP008_GOAL_ID),
        ("purpose", contract_value.get("gate_purpose"), "INITIAL_START"),
    ):
        _require_equal(errors, f"FP008 start gate contract {label}", actual, expected)
    raw_checks = contract_value.get("ordered_checks")
    checks: list[dict[str, str]] = []
    if not isinstance(raw_checks, list):
        errors.append("FP008 start gate ordered checks are missing")
    else:
        for index, item in enumerate(raw_checks, start=1):
            if (
                not isinstance(item, dict)
                or set(item) != {"check_id", "command"}
                or not isinstance(item.get("check_id"), str)
                or not isinstance(item.get("command"), str)
                or not item["command"]
            ):
                errors.append(f"FP008 start gate ordered check {index} differs")
                continue
            checks.append(
                {"check_id": item["check_id"], "command": item["command"]}
            )
    _require_equal(
        errors,
        "FP008 start gate ordered check IDs",
        [item["check_id"] for item in checks],
        FP008_START_GATE_CHECK_IDS,
    )
    forbidden = (
        "apps/web",
        "android-gateway",
        "npm",
        "pwa",
        "legacy",
        "connecteddebugandroidtest",
        " adb ",
        "validate_walksafe_full_rc_20260713.py",
    )
    if any(
        token in item["command"].lower()
        for item in checks
        for token in forbidden
    ):
        errors.append("FP008 start gate contract contains forbidden command scope")
    return errors, checks, binding, ready


def _validate_fp008_runtime_bindings(
    root: Path,
    value: Any,
) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, list) or len(value) != len(FP008_START_GATE_RUNTIME_PATHS):
        return ["FP008 start gate runtime bindings differ"]
    expected: list[dict[str, str]] = []
    for relative in FP008_START_GATE_RUNTIME_PATHS:
        path = resolve_repo_file(root, relative)
        if path is None or _contains_symlink(root, relative):
            errors.append(f"FP008 start gate runtime binding is missing or unsafe: {relative}")
            continue
        expected.append({"path": relative, "file_sha256": sha256_file(path)})
    _require_equal(errors, "FP008 start gate runtime bindings", value, expected)
    return errors


def _fp046_start_gate_contract(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
) -> tuple[
    list[str],
    list[dict[str, str]],
    dict[str, Any],
    dict[str, Any],
]:
    errors: list[str] = []
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    event_sequence = event.get("sequence")
    ready_events = [
        item
        for item in history
        if isinstance(item, dict)
        and item.get("event_type") == "GOAL_READY"
        and item.get("subject_goal_id") == FP046_GOAL_ID
        and isinstance(item.get("sequence"), int)
        and isinstance(event_sequence, int)
        and item["sequence"] < event_sequence
    ] if isinstance(history, list) else []
    if len(ready_events) != 1:
        return ["FP046 start gate READY event is missing or ambiguous"], [], {}, {}
    ready = ready_events[0]
    expected_previous_event_sha256 = ready.get("event_sha256")
    if event.get("event_type") == "WORK_SESSION_RESUMED":
        execution_sessions = [
            item
            for item in history
            if isinstance(item, dict)
            and item.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            and item.get("subject_goal_id") == FP046_GOAL_ID
            and isinstance(item.get("sequence"), int)
            and isinstance(event_sequence, int)
            and item["sequence"] < event_sequence
        ]
        previous_session = execution_sessions[-1] if execution_sessions else {}
        expected_previous_event_sha256 = previous_session.get("event_sha256")
        if (
            previous_session.get("event_type")
            not in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
            or event.get("previous_execution_session_event_sha256")
            != expected_previous_event_sha256
        ):
            errors.append("FP046 resume execution-session lineage differs")
    if (
        ready.get("sequence") != 52
        or ready.get("event_id")
        != "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP046-20260809-001"
        or ready.get("event_sha256") != event_sha256(ready)
        or event.get("previous_event_sha256") != expected_previous_event_sha256
    ):
        errors.append("FP046 start gate READY event lineage differs")

    binding = ready.get("implementation_start_gate_contract_binding")
    if (
        not isinstance(binding, dict)
        or set(binding) != FP008_START_GATE_CONTRACT_BINDING_FIELDS
    ):
        return errors + ["FP046 start gate contract binding differs"], [], {}, ready
    for label, actual, expected in (
        ("schema version", binding.get("schema_version"), "1.0"),
        (
            "document ID",
            binding.get("document_id"),
            "WS-FP046-INITIAL-START-GATE-CONTRACT-20260809-001",
        ),
        ("path", binding.get("path"), FP046_START_GATE_CONTRACT_PATH),
        (
            "contract ID",
            binding.get("contract_id"),
            "WS-FP046-INTERNAL-START-GATE-R001",
        ),
        ("contract version", binding.get("contract_version"), "2026-08-09.1"),
    ):
        _require_equal(errors, f"FP046 start gate contract {label}", actual, expected)
    if _contains_symlink(root, FP046_START_GATE_CONTRACT_PATH):
        errors.append("FP046 start gate contract path contains a symlink")
        return errors, [], binding, ready
    path = resolve_repo_file(root, binding.get("path"))
    if path is None:
        return errors + ["FP046 start gate contract path is missing or unsafe"], [], binding, ready
    file_sha256 = binding.get("file_sha256")
    if not isinstance(file_sha256, str) or not SHA256_RE.fullmatch(file_sha256):
        errors.append("FP046 start gate contract file SHA-256 is invalid")
    elif sha256_file(path) != file_sha256:
        errors.append("FP046 start gate contract file SHA-256 differs")
    try:
        contract_value = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"FP046 start gate contract cannot be loaded: {exc}"], [], binding, ready
    if set(contract_value) != FP008_START_GATE_CONTRACT_FIELDS:
        errors.append("FP046 start gate contract field set differs")
    canonical_sha256 = canonical_json_sha256(contract_value)
    _require_equal(
        errors,
        "FP046 start gate canonical contract SHA-256",
        binding.get("canonical_contract_sha256"),
        canonical_sha256,
    )
    for label, actual, expected in (
        ("schema version", contract_value.get("schema_version"), "1.0"),
        ("document ID", contract_value.get("document_id"), binding.get("document_id")),
        ("contract ID", contract_value.get("contract_id"), binding.get("contract_id")),
        ("contract version", contract_value.get("contract_version"), binding.get("contract_version")),
        ("target Goal", contract_value.get("target_goal_id"), FP046_GOAL_ID),
        (
            "target Goal content SHA-256",
            contract_value.get("target_goal_content_sha256"),
            ready.get("focus_goal_content_sha256"),
        ),
        ("purpose", contract_value.get("gate_purpose"), "INITIAL_START"),
    ):
        _require_equal(errors, f"FP046 start gate contract {label}", actual, expected)
    raw_checks = contract_value.get("ordered_checks")
    checks: list[dict[str, str]] = []
    if not isinstance(raw_checks, list):
        errors.append("FP046 start gate ordered checks are missing")
    else:
        for index, item in enumerate(raw_checks, start=1):
            if (
                not isinstance(item, dict)
                or set(item) != {"check_id", "command"}
                or not isinstance(item.get("check_id"), str)
                or not isinstance(item.get("command"), str)
                or not item["command"]
            ):
                errors.append(f"FP046 start gate ordered check {index} differs")
                continue
            checks.append(
                {"check_id": item["check_id"], "command": item["command"]}
            )
    _require_equal(
        errors,
        "FP046 start gate ordered check IDs",
        [item["check_id"] for item in checks],
        FP046_START_GATE_CHECK_IDS,
    )
    forbidden = (
        "apps/web",
        "adminapp",
        "connecteddebugandroidtest",
        " adb ",
        "device",
        "external",
        "formal",
        "deploy",
        "release",
    )
    if any(
        token in item["command"].lower()
        for item in checks
        for token in forbidden
    ):
        errors.append("FP046 start gate contract contains forbidden command scope")
    return errors, checks, binding, ready


def _validate_fp046_runtime_bindings(
    root: Path,
    value: Any,
) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, list) or len(value) != len(FP046_START_GATE_RUNTIME_PATHS):
        return ["FP046 start gate runtime bindings differ"]
    expected: list[dict[str, str]] = []
    for relative in FP046_START_GATE_RUNTIME_PATHS:
        path = resolve_repo_file(root, relative)
        if path is None or _contains_symlink(root, relative):
            errors.append(f"FP046 start gate runtime binding is missing or unsafe: {relative}")
            continue
        expected.append({"path": relative, "file_sha256": sha256_file(path)})
    _require_equal(errors, "FP046 start gate runtime bindings", value, expected)
    return errors


def _validate_start_gate(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
    goal_paths: dict[str, str],
    manifest: dict[str, Any],
    manifest_sha256: str,
    activation_sha256: str,
    activation_occurred_at: datetime | None,
) -> list[str]:
    event_id = event.get("event_id")
    if not isinstance(event_id, str):
        return ["start gate event ID is missing"]
    binding = event.get("implementation_start_gate_binding")
    expected_receipt_name = (
        "implementation-start-gate-receipt.json"
        if event.get("event_type") == "GOAL_STARTED"
        else "implementation-resume-gate-receipt.json"
    )
    expected_receipt_path = (
        f"docs/control/execution/goal-gates/{event_id}/"
        f"{expected_receipt_name}"
    )
    subject = event.get("subject_goal_id")
    gate_purpose = (
        "INITIAL_START"
        if event.get("event_type") == "GOAL_STARTED"
        else "SESSION_RESUME"
    )
    fp008_scoped = (
        event.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        and subject == FP008_GOAL_ID
    )
    fp046_scoped = (
        event.get("event_type") in {"GOAL_STARTED", "WORK_SESSION_RESUMED"}
        and subject == FP046_GOAL_ID
    )
    private_scoped = fp008_scoped or fp046_scoped
    path_errors: list[str] = []
    if not isinstance(binding, dict):
        path_errors.append("start gate binding is missing")
    else:
        _require_equal(
            path_errors,
            "start gate event-scoped receipt path",
            binding.get("path"),
            expected_receipt_path,
        )
    private_receipt_identity: (
        tuple[int, int, int, int, int, int, int, int] | None
    ) = None
    private_directory_identity: tuple[int, int] | None = None
    if private_scoped:
        (
            errors,
            receipt,
            private_receipt_identity,
            private_directory_identity,
        ) = _load_fp008_private_binding(
            root,
            binding,
            event_id=event_id,
            expected_path=expected_receipt_path,
            label=f"{event_id} start gate",
        )
    else:
        errors, receipt = _load_direct_binding(
            root,
            binding,
            label=f"{event.get('event_id', 'execution event')} start gate",
        )
    errors = path_errors + errors
    if not receipt:
        return errors
    private_contract_binding: dict[str, Any] = {}
    private_ready: dict[str, Any] = {}
    if fp008_scoped:
        contract_errors, checks, private_contract_binding, private_ready = (
            _fp008_start_gate_contract(
                root,
                event=event,
                checkpoint=checkpoint,
            )
        )
        errors.extend(contract_errors)
        expected_receipt_fields = FP008_START_GATE_RECEIPT_FIELDS
        expected_schema_version = "1.1"
    elif fp046_scoped:
        contract_errors, checks, private_contract_binding, private_ready = (
            _fp046_start_gate_contract(
                root,
                event=event,
                checkpoint=checkpoint,
            )
        )
        errors.extend(contract_errors)
        expected_receipt_fields = FP008_START_GATE_RECEIPT_FIELDS
        expected_schema_version = "1.1"
    else:
        contract = _manifest_transition_contract(manifest)
        checks = _control_checks(
            manifest,
            "implementation_start_gate_checks",
        )
        expected_receipt_fields = V24_START_GATE_RECEIPT_FIELDS
        expected_schema_version = "1.0"
    if set(receipt) != expected_receipt_fields:
        errors.append("start gate receipt field set differs")
    for label, actual, expected in (
        ("schema version", receipt.get("schema_version"), expected_schema_version),
        (
            "evidence type",
            receipt.get("evidence_type"),
            "IMPLEMENTATION_START_OR_RESUME_GATE",
        ),
        ("purpose", receipt.get("gate_purpose"), gate_purpose),
        ("status", receipt.get("status"), "PASS"),
        ("package", receipt.get("package_id"), V24_PACKAGE_ID),
        (
            "target event",
            receipt.get("target_transition_event_id"),
            event.get("event_id"),
        ),
        ("target Goal", receipt.get("target_goal_id"), subject),
        (
            "manifest",
            receipt.get("static_plan_manifest_sha256"),
            manifest_sha256,
        ),
        (
            "activation event",
            receipt.get("source_activation_event_sha256"),
            activation_sha256,
        ),
    ):
        _require_equal(errors, f"start gate {label}", actual, expected)

    if private_scoped:
        private_label = "FP008" if fp008_scoped else "FP046"
        contract_version = private_contract_binding.get("contract_version")
        contract_sha256 = private_contract_binding.get(
            "canonical_contract_sha256"
        )
        _require_equal(
            errors,
            f"{private_label} start gate contract version",
            receipt.get("check_command_contract_version"),
            contract_version,
        )
        _require_equal(
            errors,
            f"{private_label} start gate contract SHA-256",
            receipt.get("check_command_contract_sha256"),
            contract_sha256,
        )
        _require_equal(
            errors,
            f"{private_label} start gate receipt/READY contract binding",
            receipt.get("implementation_start_gate_contract_binding"),
            private_contract_binding,
        )
        _require_equal(
            errors,
            f"{private_label} start gate receipt/READY event SHA-256",
            receipt.get("source_ready_event_sha256"),
            private_ready.get("event_sha256"),
        )
        source_checkpoint_sha256 = receipt.get("source_checkpoint_sha256")
        if (
            not isinstance(source_checkpoint_sha256, str)
            or not SHA256_RE.fullmatch(source_checkpoint_sha256)
        ):
            errors.append(
                f"{private_label} start gate source checkpoint SHA-256 is invalid"
            )
        elif event.get("source_checkpoint_sha256") is not None:
            _require_equal(
                errors,
                f"{private_label} start gate receipt/event source checkpoint SHA-256",
                source_checkpoint_sha256,
                event.get("source_checkpoint_sha256"),
            )
        runtime_validator = (
            _validate_fp008_runtime_bindings
            if fp008_scoped
            else _validate_fp046_runtime_bindings
        )
        errors.extend(runtime_validator(root, receipt.get("runtime_bindings")))
    else:
        contract_version = contract.get("check_command_contract_version")
        contract_sha256 = _check_contract_sha256(
            contract_version,
            checks,
        )
        _require_equal(
            errors,
            "start gate contract version",
            receipt.get("check_command_contract_version"),
            contract_version,
        )
        _require_equal(
            errors,
            "start gate contract SHA-256",
            receipt.get("check_command_contract_sha256"),
            contract_sha256,
        )
        _require_equal(
            errors,
            "v2.4 manifest start gate IDs",
            contract.get("implementation_start_gate_check_ids"),
            [item["check_id"] for item in checks],
        )
        _require_equal(
            errors,
            "v2.4 manifest start gate contract SHA-256",
            contract.get("implementation_start_gate_check_contract_sha256"),
            contract_sha256,
        )
    goal_path = resolve_repo_file(root, goal_paths.get(str(subject)))
    if goal_path is None:
        errors.append(f"start gate target Goal path is missing: {subject}")
    else:
        _require_equal(
            errors,
            "start gate target Goal content SHA-256",
            receipt.get("target_goal_content_sha256"),
            sha256_file(goal_path),
        )
    _require_equal(
        errors,
        "start gate receipt/event repository snapshot",
        receipt.get("repository_snapshot"),
        event.get("repository_snapshot_before"),
    )
    if not private_scoped:
        lock_path = resolve_repo_file(
            root,
            "configs/walksafe_node_toolchain_lock_20260715.json",
        )
        expected_lock = (
            {
                "path": "configs/walksafe_node_toolchain_lock_20260715.json",
                "file_sha256": sha256_file(lock_path),
            }
            if lock_path is not None
            else None
        )
        _require_equal(
            errors,
            "start gate toolchain lock binding",
            receipt.get("toolchain_lock_binding"),
            expected_lock,
        )
    run_errors, repository_payload = _validate_check_runs(
        root,
        label=f"v2.4 {gate_purpose.lower()} gate",
        event_id=event_id,
        receipt=receipt,
        expected_checks=checks,
        fp008_private_evidence=private_scoped,
        fp008_event_directory_identity=private_directory_identity,
        fp008_receipt_name=expected_receipt_name,
    )
    errors.extend(run_errors)
    if (
        private_scoped
        and private_receipt_identity is not None
        and private_directory_identity is not None
    ):
        final_errors, final_receipt, _, _ = (
            _read_fp008_private_event_file(
                root,
                event_id=event_id,
                relative_path=expected_receipt_path,
                label=f"{event_id} final start gate receipt",
                expected_directory_identity=private_directory_identity,
                expected_file_identity=private_receipt_identity,
            )
        )
        errors.extend(final_errors)
        binding_digest = (
            binding.get("file_sha256") if isinstance(binding, dict) else None
        )
        if (
            final_receipt is not None
            and isinstance(binding_digest, str)
            and SHA256_RE.fullmatch(binding_digest)
            and sha256_bytes(final_receipt) != binding_digest
        ):
            errors.append("private start gate receipt changed during validation")
        errors.extend(
            _validate_fp008_gate_event_inventory(
                root,
                event_id=event_id,
                expected_checks=checks,
                expected_directory_identity=private_directory_identity,
                label=f"{event_id} final start gate",
                receipt_name=expected_receipt_name,
            )
        )
    snapshot = event.get("repository_snapshot_before")
    errors.extend(
        _validate_repository_payload(
            label="v2.4 start gate",
            event_id=event_id,
            payload=repository_payload,
            snapshot=snapshot,
        )
    )
    runs = receipt.get("check_runs")
    if isinstance(runs, list) and runs and isinstance(runs[-1], dict):
        _require_equal(
            errors,
            "start gate repository output SHA-256 binding",
            (
                snapshot.get("gate_repository_state_output_sha256")
                if isinstance(snapshot, dict)
                else None
            ),
            runs[-1].get("output_sha256"),
        )
    event_time = _parse_iso_datetime(
        errors,
        "start gate event occurred_at",
        event.get("occurred_at"),
    )
    generated_at = _parse_iso_datetime(
        errors,
        "start gate generated_at",
        receipt.get("generated_at"),
    )
    window = receipt.get("execution_window")
    started_at = _parse_iso_datetime(
        errors,
        "start gate started_at",
        window.get("started_at") if isinstance(window, dict) else None,
    )
    if (
        None not in {
            activation_occurred_at,
            started_at,
            generated_at,
            event_time,
        }
        and not (
            activation_occurred_at
            <= started_at
            <= generated_at
            <= event_time
            and event_time - generated_at <= timedelta(hours=1)
        )
    ):
        errors.append("start gate activation/freshness chronology differs")
    return errors


def _completion_hash_seed(archive: dict[str, Any]) -> dict[str, str]:
    state = archive.get("goal_execution")
    if not isinstance(state, dict):
        return {}
    result: dict[str, str] = {}
    imported = state.get("imported_predecessor_goal_bindings")
    if isinstance(imported, dict):
        for goal_id, record in imported.items():
            digest = (
                record.get("completion_event_sha256")
                if isinstance(record, dict)
                else None
            )
            if isinstance(goal_id, str) and isinstance(digest, str):
                result[goal_id] = digest
    history = state.get("transition_history")
    if isinstance(history, list):
        for event in history:
            if (
                isinstance(event, dict)
                and event.get("event_type")
                in {"GOAL_COMPLETED", "PACKAGE_COMPLETED"}
                and isinstance(event.get("subject_goal_id"), str)
                and isinstance(event.get("event_sha256"), str)
            ):
                result[event["subject_goal_id"]] = event["event_sha256"]
    return result


def _validate_imported_completion_activation(
    root: Path,
    *,
    event: dict[str, Any],
    prepared: dict[str, Any],
    archive: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    state = archive.get("goal_execution")
    if not isinstance(state, dict):
        return ["v2.4 activation predecessor completion state is missing"]
    statuses = state.get("status_by_goal")
    references = state.get("completion_evidence_by_goal")
    history = state.get("transition_history")
    if (
        not isinstance(statuses, dict)
        or not isinstance(references, dict)
        or not isinstance(history, list)
    ):
        return ["v2.4 activation predecessor completion projection is malformed"]
    completed_ids = {
        goal_id
        for goal_id, status in statuses.items()
        if isinstance(goal_id, str) and status == "COMPLETE_AT_TARGET"
    }
    expected_bindings: dict[str, dict[str, Any]] = {}
    for source_event in history:
        if not isinstance(source_event, dict):
            continue
        if source_event.get("event_type") == "PACKAGE_ACTIVATED":
            imported_bindings = source_event.get(
                "imported_completion_evidence_bindings_by_goal"
            )
            if isinstance(imported_bindings, dict):
                for goal_id, bindings in imported_bindings.items():
                    if isinstance(goal_id, str) and isinstance(bindings, dict):
                        expected_bindings[goal_id] = bindings
        elif source_event.get("event_type") in {
            "GOAL_COMPLETED",
            "PACKAGE_COMPLETED",
        }:
            subject = source_event.get("subject_goal_id")
            bindings = source_event.get("completion_evidence_bindings")
            if isinstance(subject, str) and isinstance(bindings, dict):
                expected_bindings[subject] = bindings
    expected_refs = {
        goal_id: references.get(goal_id)
        for goal_id in sorted(completed_ids)
    }
    expected_events = {
        goal_id: prepared.get("event_sha256")
        for goal_id in sorted(completed_ids)
    }
    expected_binding_map = {
        goal_id: expected_bindings.get(goal_id)
        for goal_id in sorted(completed_ids)
    }
    _require_equal(
        errors,
        "v2.4 activation imported completion event map",
        event.get("imported_completion_event_sha256_by_goal"),
        expected_events,
    )
    _require_equal(
        errors,
        "v2.4 activation imported completion reference map",
        event.get("imported_completion_evidence_refs_by_goal"),
        expected_refs,
    )
    _require_equal(
        errors,
        "v2.4 activation imported completion binding map",
        event.get("imported_completion_evidence_bindings_by_goal"),
        expected_binding_map,
    )
    for goal_id, bindings in expected_binding_map.items():
        if not isinstance(bindings, dict):
            errors.append(
                f"v2.4 activation completion bindings are missing: {goal_id}"
            )
            continue
        if set(bindings) != set(expected_refs.get(goal_id, [])):
            errors.append(
                f"v2.4 activation completion binding roles differ: {goal_id}"
            )
        for role, binding in bindings.items():
            path = resolve_repo_file(
                root,
                binding.get("path") if isinstance(binding, dict) else None,
            )
            if (
                not isinstance(binding, dict)
                or set(binding)
                != {"role", "document_id", "path", "file_sha256"}
                or binding.get("role") != role
                or path is None
                or binding.get("file_sha256") != sha256_file(path)
            ):
                errors.append(
                    "v2.4 activation completion evidence differs: "
                    f"{goal_id}/{role}"
                )
    return errors


def _expected_status_change(
    event_type: str,
    event: dict[str, Any],
    statuses: dict[str, str],
) -> tuple[dict[str, str] | None, str | None]:
    subject = event.get("subject_goal_id")
    materialized = event.get("materialized_goal_id")
    if event_type in {
        "PACKAGE_ACTIVATED",
        "CANONICAL_BINDINGS_UPDATED",
        "WORK_SESSION_RESUMED",
        "GOAL_FOCUS_CHANGED",
    }:
        return {}, None
    if event_type == "GOAL_STARTED":
        return ({str(subject): "IN_PROGRESS"} if isinstance(subject, str) else None), None
    if event_type == "GOAL_COMPLETED":
        return (
            {str(subject): "COMPLETE_AT_TARGET"}
            if isinstance(subject, str)
            else None
        ), None
    if event_type == "GOAL_MATERIALIZED":
        return (
            {str(materialized): "PLANNED"}
            if isinstance(materialized, str)
            else None
        ), None
    if event_type == "GOAL_READY":
        return ({str(subject): "READY"} if isinstance(subject, str) else None), None
    if event_type == "GOAL_SUPERSEDED":
        if isinstance(subject, str) and isinstance(materialized, str):
            return {subject: "SUPERSEDED", materialized: "PLANNED"}, None
        return None, None
    if event_type == "BLOCKER_RECORDED":
        changes = event.get("status_changes")
        if (
            isinstance(subject, str)
            and isinstance(changes, dict)
            and changes.get(subject)
            in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
        ):
            return dict(changes), None
        return None, None
    if event_type == "BLOCKER_RESOLVED":
        changes = event.get("status_changes")
        if (
            isinstance(subject, str)
            and isinstance(changes, dict)
            and changes.get(subject) in {"PLANNED", "READY", "IN_PROGRESS"}
        ):
            return dict(changes), None
        return None, None
    if event_type == "PACKAGE_COMPLETED":
        return (
            {str(subject): "COMPLETE_AT_TARGET"}
            if isinstance(subject, str)
            else None
        ), None
    return None, f"unsupported event type: {event_type}"


def _expected_from_to(
    event_type: str,
    event: dict[str, Any],
    statuses: dict[str, str],
    changes: dict[str, str],
) -> tuple[str, str] | None:
    subject = event.get("subject_goal_id")
    materialized = event.get("materialized_goal_id")
    if event_type == "PACKAGE_ACTIVATED":
        focus = event.get("focus_goal_id")
        status = statuses.get(str(focus), "")
        return status, status
    if event_type == "GOAL_MATERIALIZED":
        from_status = event.get("from_status")
        if from_status not in ("", None):
            return None
        return from_status, "PLANNED"
    if event_type == "GOAL_SUPERSEDED":
        return statuses.get(str(subject), ""), "SUPERSEDED"
    if event_type in {
        "GOAL_STARTED",
        "GOAL_COMPLETED",
        "GOAL_READY",
        "BLOCKER_RECORDED",
        "BLOCKER_RESOLVED",
        "PACKAGE_COMPLETED",
    }:
        if not isinstance(subject, str):
            return None
        return statuses.get(subject, ""), changes.get(subject, "")
    if event_type == "WORK_SESSION_RESUMED":
        return "IN_PROGRESS", "IN_PROGRESS"
    if event_type == "CANONICAL_BINDINGS_UPDATED":
        producer = event.get("produced_by_goal_id")
        focus = event.get("focus_goal_id")
        goal_id = producer if isinstance(producer, str) else focus
        status = statuses.get(str(goal_id), "")
        return status, status
    if event_type == "GOAL_FOCUS_CHANGED":
        previous = event.get("previous_focus_goal_id")
        focus = event.get("focus_goal_id")
        return (
            statuses.get(str(previous), ""),
            statuses.get(str(focus), ""),
        )
    if materialized is not None:
        return "", changes.get(str(materialized), "")
    return None


def validate_generic_event_order(
    history: list[dict[str, Any]],
) -> list[str]:
    """Pure append-only grammar used by tests and the repository validator.

    No package path, receipt, or FP-specific identifier is needed here.  A
    test may therefore prove that several arbitrary future Goal cycles remain
    legal without modifying this checker.
    """
    errors: list[str] = []
    if not isinstance(history, list) or not history:
        return ["generic history is missing"]
    statuses: dict[str, str] = {}
    pending_producer: str | None = None
    previous_hash = ""
    manifest_hash: str | None = None
    seen_ids: set[str] = set()
    activated = False
    completed = False
    for index, event in enumerate(history, start=1):
        label = f"generic event {index}"
        if not isinstance(event, dict):
            errors.append(f"{label} is not an object")
            continue
        event_type = event.get("event_type")
        event_id = event.get("event_id")
        if event.get("sequence") != index:
            errors.append(f"{label} sequence differs")
        if (
            not isinstance(event_id, str)
            or not SAFE_ID_RE.fullmatch(event_id)
            or event_id in seen_ids
        ):
            errors.append(f"{label} ID is invalid or duplicated")
        else:
            seen_ids.add(event_id)
        if event_type not in ALLOWED_EVENT_TYPES:
            errors.append(f"{label} type is invalid")
        if event.get("previous_event_sha256") != previous_hash:
            errors.append(f"{label} previous hash differs")
        if event.get("event_sha256") != event_sha256(event):
            errors.append(f"{label} seal differs")
        current_manifest = event.get("static_plan_manifest_sha256")
        if index == 1:
            manifest_hash = (
                current_manifest if isinstance(current_manifest, str) else None
            )
        elif current_manifest != manifest_hash:
            errors.append(f"{label} manifest binding differs")
        changes = event.get("status_changes")
        if not isinstance(changes, dict) or any(
            not isinstance(goal_id, str)
            or not isinstance(status, str)
            or status not in RUNTIME_STATUSES
            for goal_id, status in (
                changes.items() if isinstance(changes, dict) else []
            )
        ):
            errors.append(f"{label} status_changes are invalid")
            changes = {}

        if index == 1:
            if event_type != "PACKAGE_PREPARED":
                errors.append("generic history must begin with PACKAGE_PREPARED")
            statuses.update(changes)
            previous_hash = str(event.get("event_sha256", ""))
            continue
        if index == 2:
            if event_type != "PACKAGE_ACTIVATED":
                errors.append(
                    "generic PACKAGE_ACTIVATED must immediately follow preparation"
                )
            if changes:
                errors.append("generic PACKAGE_ACTIVATED changes Goal status")
            expected_boundary = _expected_from_to(
                str(event_type),
                event,
                statuses,
                changes,
            )
            if (
                expected_boundary is None
                or (
                    event.get("from_status"),
                    event.get("to_status"),
                )
                != expected_boundary
            ):
                errors.append(
                    "generic PACKAGE_ACTIVATED from/to boundary differs"
                )
            activated = event_type == "PACKAGE_ACTIVATED"
            previous_hash = str(event.get("event_sha256", ""))
            continue
        if not activated:
            errors.append(f"{label} precedes package activation")
        if completed:
            errors.append(f"{label} follows terminal PACKAGE_COMPLETED")

        subject = event.get("subject_goal_id")
        materialized = event.get("materialized_goal_id")
        if pending_producer is not None and not (
            event_type == "GOAL_COMPLETED"
            and subject == pending_producer
        ):
            errors.append(
                f"{label} violates canonical producer completion order"
            )
        expected_changes, transition_error = _expected_status_change(
            str(event_type),
            event,
            statuses,
        )
        if transition_error:
            errors.append(f"{label} {transition_error}")
        elif expected_changes is None:
            errors.append(f"{label} subject transition is malformed")
        elif changes != expected_changes:
            errors.append(f"{label} status transition differs")

        before = dict(statuses)
        expected_boundary = _expected_from_to(
            str(event_type),
            event,
            before,
            changes,
        )
        if expected_boundary is None:
            errors.append(f"{label} from/to boundary cannot be derived")
        elif (
            event.get("from_status"),
            event.get("to_status"),
        ) != expected_boundary:
            errors.append(f"{label} from/to boundary differs")
        if event_type == "GOAL_STARTED":
            if not isinstance(subject, str) or before.get(subject) != "READY":
                errors.append(f"{label} does not start a READY Goal")
            if any(status == "IN_PROGRESS" for status in before.values()):
                errors.append(f"{label} creates a second IN_PROGRESS Goal")
        elif event_type == "WORK_SESSION_RESUMED":
            if (
                not isinstance(subject, str)
                or before.get(subject) != "IN_PROGRESS"
            ):
                errors.append(f"{label} does not resume IN_PROGRESS work")
        elif event_type == "CANONICAL_BINDINGS_UPDATED":
            producer = event.get("produced_by_goal_id")
            if producer not in {None, ""}:
                if (
                    not isinstance(producer, str)
                    or before.get(producer) != "IN_PROGRESS"
                ):
                    errors.append(
                        f"{label} producer is not the IN_PROGRESS Goal"
                    )
                elif pending_producer is None:
                    pending_producer = producer
                else:
                    errors.append(f"{label} nests a producer transaction")
        elif event_type == "GOAL_COMPLETED":
            if (
                not isinstance(subject, str)
                or before.get(subject) != "IN_PROGRESS"
            ):
                errors.append(f"{label} does not complete IN_PROGRESS work")
            if pending_producer == subject:
                pending_producer = None
        elif event_type == "GOAL_MATERIALIZED":
            if not isinstance(materialized, str) or materialized in before:
                errors.append(f"{label} materialized Goal already exists")
            predecessor = event.get("predecessor_goal_id")
            if (
                not isinstance(predecessor, str)
                or before.get(predecessor) != "COMPLETE_AT_TARGET"
            ):
                errors.append(
                    f"{label} predecessor is not COMPLETE_AT_TARGET"
                )
        elif event_type == "GOAL_READY":
            if (
                not isinstance(subject, str)
                or before.get(subject) != "PLANNED"
            ):
                errors.append(f"{label} does not ready a PLANNED Goal")
        elif event_type == "GOAL_SUPERSEDED":
            if (
                not isinstance(subject, str)
                or subject not in before
                or before.get(subject) == "SUPERSEDED"
                or not isinstance(materialized, str)
                or materialized in before
            ):
                errors.append(f"{label} supersession boundary differs")
        elif event_type == "BLOCKER_RECORDED":
            if (
                not isinstance(subject, str)
                or before.get(subject)
                not in {"PLANNED", "READY", "IN_PROGRESS"}
            ):
                errors.append(f"{label} blocker target differs")
        elif event_type == "BLOCKER_RESOLVED":
            if (
                not isinstance(subject, str)
                or before.get(subject)
                not in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
            ):
                errors.append(f"{label} blocker resolution target differs")
        elif event_type == "PACKAGE_COMPLETED":
            completed = True
        statuses.update(changes)
        previous_hash = str(event.get("event_sha256", ""))
    if pending_producer is not None:
        errors.append("generic history ends inside a producer transaction")
    return errors


def validate_transition_replay(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
    manifest_path: Path = V24_MANIFEST_RELATIVE,
    *,
    expected_prepared_sha256: str | None = None,
    expected_authorization_sha256: str | None = None,
) -> list[str]:
    """Replay v2.4 without enumerating FP011, FP012, ... event tuples."""
    errors: list[str] = []
    manifest_file = resolve_repo_file(root, manifest_path)
    if manifest_file is None:
        return ["v2.4 manifest is missing"]
    try:
        manifest = load_json(manifest_file)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return [f"v2.4 manifest cannot be loaded: {exc}"]
    manifest_sha256 = sha256_file(manifest_file)
    if (
        not EXPECTED_V24_MANIFEST_SHA256.startswith("__FINALIZE_")
        and manifest_sha256 != EXPECTED_V24_MANIFEST_SHA256
    ):
        errors.append("v2.4 manifest trust anchor differs")
    _require_equal(
        errors,
        "v2.4 manifest package ID",
        manifest.get("package_id"),
        V24_PACKAGE_ID,
    )
    _require_equal(
        errors,
        "v2.4 manifest plan version",
        manifest.get("plan_version"),
        V24_PLAN_VERSION,
    )

    state = checkpoint.get("goal_execution")
    archived_state = archive.get("goal_execution")
    if not isinstance(state, dict):
        return errors + ["v2.4 checkpoint goal_execution is missing"]
    if not isinstance(archived_state, dict):
        return errors + ["v2.3 archive goal_execution is missing"]
    errors.extend(
        validate_v24_manifest_and_package(
            root,
            manifest=manifest,
            manifest_path=manifest_path,
            state=state,
        )
    )
    errors.extend(
        validate_imported_goal_bindings(
            root,
            state=state,
            archive=archive,
        )
    )
    for label, actual, expected in (
        ("package ID", state.get("package_id"), V24_PACKAGE_ID),
        ("plan version", state.get("static_plan_version"), V24_PLAN_VERSION),
        (
            "manifest binding",
            state.get("static_plan_manifest_sha256"),
            manifest_sha256,
        ),
    ):
        _require_equal(errors, f"v2.4 {label}", actual, expected)

    history = state.get("transition_history")
    if not isinstance(history, list) or not history:
        return errors + ["v2.4 transition history is missing"]
    errors.extend(
        f"generic order: {error}"
        for error in validate_generic_event_order(history)
    )
    prepared = history[0] if isinstance(history[0], dict) else {}
    archived_statuses = archived_state.get("status_by_goal")
    if not isinstance(archived_statuses, dict):
        return errors + ["v2.3 archive status map is missing"]
    archived_bindings = canonical_binding_snapshot(archive)
    imported = state.get("imported_predecessor_goal_bindings")
    if not isinstance(imported, dict):
        imported = {}
        errors.append("v2.4 imported predecessor Goal bindings are missing")

    previous_hash = ""
    statuses: dict[str, str] = {}
    completion_hashes = _completion_hash_seed(archive)
    pending_producer: str | None = None
    activation_hash: str | None = None
    activation_occurred_at: datetime | None = None
    goal_paths = _goal_path_by_id(archive, checkpoint)
    seen_ids: set[str] = set()
    previous_occurred_at: datetime | None = None
    package_completed = False
    latest_canonical_bindings = archived_bindings
    latest_canonical_bindings_label = "v2.3 archive"
    latest_completion_evidence = archived_state.get(
        "completion_evidence_by_goal"
    )
    latest_archived_completion_evidence = archived_state.get(
        "archived_completion_evidence_by_goal"
    )
    latest_inventory = archived_state.get("dynamic_goal_inventory")
    latest_children = archived_state.get(
        "materialized_child_goal_ids_by_parent"
    )
    latest_blockers = archived_state.get("blockers_by_goal")
    latest_resolution_ids = [
        record.get("resolution_id")
        for record in archived_state.get("blocker_resolution_history", [])
        if isinstance(record, dict)
    ]

    for index, raw_event in enumerate(history, start=1):
        label = f"v2.4 event {index}"
        if not isinstance(raw_event, dict):
            errors.append(f"{label} is not an object")
            continue
        event = raw_event
        event_type = event.get("event_type")
        event_id = event.get("event_id")
        if event.get("sequence") != index:
            errors.append(f"{label} sequence differs")
        if (
            not isinstance(event_id, str)
            or not SAFE_ID_RE.fullmatch(event_id)
            or event_id in seen_ids
        ):
            errors.append(f"{label} ID is invalid or duplicated")
        else:
            seen_ids.add(event_id)
        if event_type not in ALLOWED_EVENT_TYPES:
            errors.append(f"{label} type is invalid")
        if event.get("previous_event_sha256") != previous_hash:
            errors.append(f"{label} previous hash differs")
        actual_hash = event_sha256(event)
        if event.get("event_sha256") != actual_hash:
            errors.append(f"{label} seal differs")
        if event.get("static_plan_manifest_sha256") != manifest_sha256:
            errors.append(f"{label} manifest binding differs")
        occurred_at = _parse_iso_datetime(
            errors,
            f"{label} occurred_at",
            event.get("occurred_at"),
        )
        if (
            occurred_at is not None
            and previous_occurred_at is not None
            and occurred_at <= previous_occurred_at
        ):
            errors.append(f"{label} time is not strictly increasing")
        if occurred_at is not None:
            _require_equal(
                errors,
                f"{label} occurred_on",
                event.get("occurred_on"),
                occurred_at.date().isoformat(),
            )

        changes = event.get("status_changes")
        if not isinstance(changes, dict) or any(
            not isinstance(goal_id, str)
            or not isinstance(status, str)
            or status not in RUNTIME_STATUSES
            for goal_id, status in (
                changes.items() if isinstance(changes, dict) else []
            )
        ):
            errors.append(f"{label} status_changes are invalid")
            changes = {}

        if index == 1:
            _require_equal(
                errors,
                "v2.4 initial event type",
                event_type,
                "PACKAGE_PREPARED",
            )
            _require_equal(
                errors,
                "v2.4 initial predecessor tail",
                event.get("supersedes_event_sha256"),
                V23_TAIL_SHA256,
            )
            _require_equal(
                errors,
                "v2.4 imported status snapshot",
                changes,
                archived_statuses,
            )
            _require_equal(
                errors,
                "v2.4 imported canonical snapshot",
                event.get("canonical_binding_snapshot_after"),
                archived_bindings,
            )
            _require_equal(
                errors,
                "v2.4 initial focus",
                event.get("focus_goal_id"),
                FP011_GOAL_ID,
            )
            for field, expected in (
                (
                    "completion_evidence_by_goal_after",
                    archived_state.get("completion_evidence_by_goal"),
                ),
                (
                    "archived_completion_evidence_by_goal_after",
                    archived_state.get("archived_completion_evidence_by_goal"),
                ),
                (
                    "dynamic_goal_inventory_after",
                    archived_state.get("dynamic_goal_inventory"),
                ),
                (
                    "materialized_child_goal_ids_by_parent_after",
                    archived_state.get(
                        "materialized_child_goal_ids_by_parent"
                    ),
                ),
                ("blockers_after", archived_state.get("blockers_by_goal")),
                (
                    "blocker_resolution_ids_after",
                    [
                        record.get("resolution_id")
                        for record in archived_state.get(
                            "blocker_resolution_history",
                            [],
                        )
                        if isinstance(record, dict)
                    ],
                ),
                (
                    "bootstrap_consumed_policy_gap_pairs",
                    archived_state.get(
                        "bootstrap_consumed_policy_gap_pairs"
                    ),
                ),
            ):
                _require_equal(
                    errors,
                    f"v2.4 initial {field}",
                    event.get(field),
                    expected,
                )
            _require_equal(
                errors,
                "v2.4 initial from status",
                event.get("from_status"),
                "",
            )
            _require_equal(
                errors,
                "v2.4 initial to status",
                event.get("to_status"),
                "READY",
            )
            predecessor = event.get("active_predecessor_import")
            expected_import = {
                "source_package_id": V23_PACKAGE_ID,
                "source_plan_version": V23_PLAN_VERSION,
                "source_manifest_path": V23_MANIFEST_RELATIVE.as_posix(),
                "source_manifest_sha256": V23_MANIFEST_SHA256,
                "source_checkpoint_path": Path(archive_path_for_event(
                    root,
                    archive,
                )).as_posix(),
                "source_checkpoint_raw_sha256": V23_ARCHIVE_RAW_SHA256,
                "source_transition_event_count": V23_EVENT_COUNT,
                "source_transition_history_anchor_sha256": V23_TAIL_SHA256,
                "imported_predecessor_goal_bindings_sha256": (
                    canonical_json_sha256(imported)
                ),
                "canonical_binding_snapshot_sha256": (
                    canonical_json_sha256(archived_bindings)
                ),
            }
            if not isinstance(predecessor, dict):
                errors.append("v2.4 active predecessor import is missing")
            else:
                for key, expected in expected_import.items():
                    _require_equal(
                        errors,
                        f"v2.4 active predecessor import {key}",
                        predecessor.get(key),
                        expected,
                    )
            if (
                expected_prepared_sha256 is not None
                and event.get("event_sha256") != expected_prepared_sha256
            ):
                errors.append("v2.4 prepared-event trust anchor differs")
            statuses.update(
                {
                    goal_id: status
                    for goal_id, status in changes.items()
                    if isinstance(goal_id, str) and isinstance(status, str)
                }
            )
        else:
            if package_completed:
                errors.append(f"{label} follows terminal PACKAGE_COMPLETED")
            if index == 2:
                _require_equal(
                    errors,
                    "v2.4 activation event type",
                    event_type,
                    "PACKAGE_ACTIVATED",
                )
                if set(event) != V24_ACTIVATION_EVENT_FIELDS:
                    errors.append(
                        "v2.4 activation event field set differs"
                    )
                _require_equal(
                    errors,
                    "v2.4 activation status changes",
                    changes,
                    {},
                )
                _require_equal(
                    errors,
                    "v2.4 activation focus",
                    event.get("focus_goal_id"),
                    FP011_GOAL_ID,
                )
                errors.extend(
                    _validate_activation_receipts(
                        root,
                        event=event,
                        prepared=prepared,
                        checkpoint=checkpoint,
                        activation_is_tail=index == len(history),
                        manifest=manifest,
                        manifest_sha256=manifest_sha256,
                        expected_authorization_sha256=(
                            expected_authorization_sha256
                        ),
                    )
                )
                errors.extend(
                    _validate_imported_completion_activation(
                        root,
                        event=event,
                        prepared=prepared,
                        archive=archive,
                    )
                )
                activation_hash = event.get("event_sha256")
                activation_occurred_at = occurred_at
            elif index == 3:
                _require_equal(
                    errors,
                    "v2.4 first execution event type",
                    event_type,
                    "GOAL_STARTED",
                )
                if set(event) != V24_FIRST_START_EVENT_FIELDS:
                    errors.append(
                        "v2.4 first execution event field set differs"
                    )
                _require_equal(
                    errors,
                    "v2.4 first execution subject",
                    event.get("subject_goal_id"),
                    FP011_GOAL_ID,
                )

            subject = event.get("subject_goal_id")
            materialized = event.get("materialized_goal_id")
            if pending_producer is not None and not (
                event_type == "GOAL_COMPLETED"
                and subject == pending_producer
            ):
                errors.append(
                    f"{label} violates canonical producer completion order"
                )

            expected_changes, transition_error = _expected_status_change(
                str(event_type),
                event,
                statuses,
            )
            if transition_error:
                errors.append(f"{label} {transition_error}")
            elif expected_changes is None:
                errors.append(f"{label} subject transition is malformed")
            elif changes != expected_changes:
                errors.append(f"{label} status transition differs")

            before = dict(statuses)
            expected_boundary = _expected_from_to(
                str(event_type),
                event,
                before,
                changes,
            )
            if expected_boundary is None:
                errors.append(f"{label} from/to boundary cannot be derived")
            elif (
                event.get("from_status"),
                event.get("to_status"),
            ) != expected_boundary:
                errors.append(f"{label} from/to boundary differs")
            if event_type == "PACKAGE_ACTIVATED":
                pass
            elif event_type == "GOAL_STARTED":
                if not isinstance(subject, str) or before.get(subject) != "READY":
                    errors.append(f"{label} does not start a READY Goal")
                if any(status == "IN_PROGRESS" for status in before.values()):
                    errors.append(f"{label} creates a second IN_PROGRESS Goal")
                if activation_hash is None:
                    errors.append(f"{label} precedes package activation")
                else:
                    errors.extend(
                        _validate_start_gate(
                            root,
                            event=event,
                            checkpoint=checkpoint,
                            goal_paths=goal_paths,
                            manifest=manifest,
                            manifest_sha256=manifest_sha256,
                            activation_sha256=activation_hash,
                            activation_occurred_at=activation_occurred_at,
                        )
                    )
            elif event_type == "WORK_SESSION_RESUMED":
                if (
                    not isinstance(subject, str)
                    or before.get(subject) != "IN_PROGRESS"
                ):
                    errors.append(f"{label} does not resume IN_PROGRESS work")
                if activation_hash is not None:
                    errors.extend(
                        _validate_start_gate(
                            root,
                            event=event,
                            checkpoint=checkpoint,
                            goal_paths=goal_paths,
                            manifest=manifest,
                            manifest_sha256=manifest_sha256,
                            activation_sha256=activation_hash,
                            activation_occurred_at=activation_occurred_at,
                        )
                    )
            elif event_type == "CANONICAL_BINDINGS_UPDATED":
                producer = event.get("produced_by_goal_id")
                if producer not in {None, ""}:
                    if (
                        not isinstance(producer, str)
                        or before.get(producer) != "IN_PROGRESS"
                    ):
                        errors.append(
                            f"{label} producer is not the IN_PROGRESS Goal"
                        )
                    elif pending_producer is None:
                        pending_producer = producer
                    else:
                        errors.append(f"{label} nests a producer transaction")
            elif event_type == "GOAL_COMPLETED":
                if (
                    not isinstance(subject, str)
                    or before.get(subject) != "IN_PROGRESS"
                ):
                    errors.append(f"{label} does not complete IN_PROGRESS work")
                if pending_producer == subject:
                    pending_producer = None
                if isinstance(subject, str):
                    completion_hashes[subject] = str(
                        event.get("event_sha256", "")
                    )
            elif event_type == "GOAL_MATERIALIZED":
                if (
                    not isinstance(materialized, str)
                    or materialized in before
                ):
                    errors.append(f"{label} materialized Goal already exists")
                predecessor = event.get("predecessor_goal_id")
                if (
                    not isinstance(predecessor, str)
                    or before.get(predecessor) != "COMPLETE_AT_TARGET"
                ):
                    errors.append(
                        f"{label} predecessor is not COMPLETE_AT_TARGET"
                    )
                inventory = state.get("dynamic_goal_inventory")
                record = (
                    inventory.get(materialized)
                    if isinstance(inventory, dict)
                    and isinstance(materialized, str)
                    else None
                )
                if not isinstance(record, dict):
                    errors.append(f"{label} dynamic inventory record is missing")
                elif (
                    record.get("path") != event.get("materialized_goal_path")
                    or record.get("materialized_event_sha256")
                    != event.get("event_sha256")
                ):
                    errors.append(f"{label} dynamic inventory binding differs")
            elif event_type == "GOAL_READY":
                if (
                    not isinstance(subject, str)
                    or before.get(subject) != "PLANNED"
                ):
                    errors.append(f"{label} does not ready a PLANNED Goal")
                basis = event.get("readiness_basis")
                dependencies = (
                    basis.get("dependency_completion_events")
                    if isinstance(basis, dict)
                    else None
                )
                if not isinstance(dependencies, list):
                    errors.append(f"{label} readiness basis is missing")
                else:
                    for dependency in dependencies:
                        goal_id = (
                            dependency.get("goal_id")
                            if isinstance(dependency, dict)
                            else None
                        )
                        digest = (
                            dependency.get("event_sha256")
                            if isinstance(dependency, dict)
                            else None
                        )
                        if (
                            not isinstance(goal_id, str)
                            or before.get(goal_id) != "COMPLETE_AT_TARGET"
                            or completion_hashes.get(goal_id) != digest
                        ):
                            errors.append(
                                f"{label} readiness dependency differs"
                            )
            elif event_type == "GOAL_SUPERSEDED":
                if (
                    not isinstance(subject, str)
                    or subject not in before
                    or before.get(subject) == "SUPERSEDED"
                    or not isinstance(materialized, str)
                    or materialized in before
                ):
                    errors.append(f"{label} supersession boundary differs")
            elif event_type == "BLOCKER_RECORDED":
                if (
                    not isinstance(subject, str)
                    or before.get(subject)
                    not in {"PLANNED", "READY", "IN_PROGRESS"}
                ):
                    errors.append(f"{label} blocker target differs")
            elif event_type == "BLOCKER_RESOLVED":
                if (
                    not isinstance(subject, str)
                    or before.get(subject)
                    not in {"AWAITING_USER", "AWAITING_EXTERNAL", "BLOCKED"}
                ):
                    errors.append(f"{label} blocker resolution target differs")
            elif event_type == "PACKAGE_COMPLETED":
                package_completed = True

            statuses.update(
                {
                    goal_id: status
                    for goal_id, status in changes.items()
                    if isinstance(goal_id, str) and isinstance(status, str)
                }
            )

        runtime = event.get("runtime_after")
        if not isinstance(runtime, dict):
            errors.append(f"{label} runtime_after is missing")
        elif event.get("focus_goal_id") != runtime.get("focus_goal_id"):
            errors.append(f"{label} focus/runtime focus differs")
        else:
            expected_runtime_lifecycle = (
                ("READY_NOT_ACTIVATED", "PREPARED_NOT_ACTIVATED")
                if index == 1
                else ("COMPLETED", "COMPLETED")
                if package_completed
                else ("ACTIVE", "ACTIVE")
            )
            _require_equal(
                errors,
                f"{label} runtime lifecycle",
                (
                    runtime.get("activation_status"),
                    runtime.get("package_status"),
                ),
                expected_runtime_lifecycle,
            )
            focus_id = runtime.get("focus_goal_id")
            if isinstance(focus_id, str) and focus_id:
                _require_equal(
                    errors,
                    f"{label} runtime focus path",
                    runtime.get("focus_goal_path"),
                    goal_paths.get(focus_id),
                )
        if "canonical_binding_snapshot_after" in event:
            candidate_bindings = event.get("canonical_binding_snapshot_after")
            if not isinstance(candidate_bindings, dict):
                errors.append(f"{label} canonical binding snapshot is malformed")
            else:
                latest_canonical_bindings = candidate_bindings
                latest_canonical_bindings_label = label
                for role, binding in candidate_bindings.items():
                    path = resolve_repo_file(
                        root,
                        binding.get("path")
                        if isinstance(binding, dict)
                        else None,
                    )
                    if (
                        not isinstance(role, str)
                        or not isinstance(binding, dict)
                        or path is None
                        or not isinstance(binding.get("file_sha256"), str)
                        or not SHA256_RE.fullmatch(binding["file_sha256"])
                    ):
                        errors.append(
                            f"{label} canonical binding is malformed: {role}"
                        )
        for event_field, current_value_name in (
            ("completion_evidence_by_goal_after", "completion"),
            (
                "archived_completion_evidence_by_goal_after",
                "archived_completion",
            ),
            ("dynamic_goal_inventory_after", "inventory"),
            ("materialized_child_goal_ids_by_parent_after", "children"),
            ("blockers_after", "blockers"),
            ("blocker_resolution_ids_after", "resolution_ids"),
        ):
            if event_field not in event:
                continue
            value = event.get(event_field)
            if current_value_name == "completion":
                latest_completion_evidence = value
            elif current_value_name == "archived_completion":
                latest_archived_completion_evidence = value
            elif current_value_name == "inventory":
                latest_inventory = value
            elif current_value_name == "children":
                latest_children = value
            elif current_value_name == "blockers":
                latest_blockers = value
            else:
                latest_resolution_ids = value
        previous_hash = str(event.get("event_sha256", ""))
        previous_occurred_at = occurred_at

    for role, binding in latest_canonical_bindings.items():
        path = resolve_repo_file(
            root,
            binding.get("path") if isinstance(binding, dict) else None,
        )
        if (
            isinstance(role, str)
            and isinstance(binding, dict)
            and path is not None
            and isinstance(binding.get("file_sha256"), str)
            and SHA256_RE.fullmatch(binding["file_sha256"])
            and binding["file_sha256"] != sha256_file(path)
        ):
            errors.append(
                f"{latest_canonical_bindings_label} "
                f"canonical binding differs: {role}"
            )

    if package_completed:
        expected_lifecycle = ("COMPLETED", "COMPLETED")
    elif len(history) == 1:
        expected_lifecycle = ("READY_NOT_ACTIVATED", "PREPARED_NOT_ACTIVATED")
    else:
        expected_lifecycle = ("ACTIVE", "ACTIVE")
    _require_equal(
        errors,
        "v2.4 package lifecycle",
        (state.get("activation_status"), state.get("package_status")),
        expected_lifecycle,
    )
    _require_equal(
        errors,
        "v2.4 replayed status map",
        state.get("status_by_goal"),
        statuses,
    )
    _require_equal(
        errors,
        "v2.4 history anchor",
        state.get("transition_history_anchor_sha256"),
        previous_hash,
    )
    for label, actual, expected in (
        (
            "canonical bindings",
            canonical_binding_snapshot(checkpoint),
            latest_canonical_bindings,
        ),
        (
            "completion evidence",
            state.get("completion_evidence_by_goal"),
            latest_completion_evidence,
        ),
        (
            "archived completion evidence",
            state.get("archived_completion_evidence_by_goal"),
            latest_archived_completion_evidence,
        ),
        (
            "dynamic Goal inventory",
            state.get("dynamic_goal_inventory"),
            latest_inventory,
        ),
        (
            "materialized child map",
            state.get("materialized_child_goal_ids_by_parent"),
            latest_children,
        ),
        ("blocker map", state.get("blockers_by_goal"), latest_blockers),
        (
            "blocker resolution IDs",
            [
                record.get("resolution_id")
                for record in state.get("blocker_resolution_history", [])
                if isinstance(record, dict)
            ],
            latest_resolution_ids,
        ),
        (
            "validation cutoff",
            state.get("validation_cutoff_at"),
            (
                previous_occurred_at.isoformat()
                if previous_occurred_at is not None
                else None
            ),
        ),
    ):
        _require_equal(errors, f"v2.4 final {label}", actual, expected)
    final_runtime = history[-1].get("runtime_after")
    if not isinstance(final_runtime, dict):
        errors.append("v2.4 final runtime is missing")
    else:
        for field in (
            "focus_goal_id",
            "focus_goal_path",
            "focus_work_item_id",
            "focus_source",
            "ready_frontier_goal_ids",
            "blocked_goal_ids",
            "pending_questions",
            "open_question_count",
            "activation_status",
            "package_status",
        ):
            _require_equal(
                errors,
                f"v2.4 final runtime/state {field}",
                final_runtime.get(field),
                state.get(field),
            )
        for field in ("artifact_work_queue", "completion_boundary"):
            if field in final_runtime:
                _require_equal(
                    errors,
                    f"v2.4 final runtime/state {field}",
                    final_runtime.get(field),
                    state.get(field),
                )
                continue
            _require_equal(
                errors,
                f"v2.4 final runtime/state {field} SHA-256",
                final_runtime.get(f"{field}_sha256"),
                canonical_json_sha256(state.get(field)),
            )
    if len(history) == 3:
        _require_equal(
            errors,
            "v2.4 first started FP011 status",
            statuses.get(FP011_GOAL_ID),
            "IN_PROGRESS",
        )
    if pending_producer is not None:
        errors.append("v2.4 history ends inside a producer transaction")
    return errors


def archive_path_for_event(root: Path, archive: dict[str, Any]) -> str:
    """Return the production archive path; kept explicit for event hashing."""
    del root, archive
    return V23_ARCHIVE_RELATIVE.as_posix()


def validate_generic_legal_tail(
    root: Path,
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
    manifest_path: Path = V24_MANIFEST_RELATIVE,
) -> list[str]:
    """Public test hook: all legal-tail decisions come from generic replay."""
    return validate_transition_replay(
        root,
        checkpoint,
        archive,
        manifest_path,
    )


def validate_prepared_checkpoint_projection(
    checkpoint: dict[str, Any],
    archive: dict[str, Any],
) -> list[str]:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(history, list) or len(history) != 1:
        return []
    errors: list[str] = []
    allowed_changes = {
        "schema_version",
        "metadata",
        "working_tree_snapshot",
        "goal_execution",
        "session_handoff",
    }
    for key in sorted(set(archive) | set(checkpoint)):
        if key not in allowed_changes:
            _require_equal(
                errors,
                f"v2.4 prepared predecessor projection {key}",
                checkpoint.get(key),
                archive.get(key),
            )
    metadata = checkpoint.get("metadata")
    if not isinstance(metadata, dict):
        errors.append("v2.4 prepared checkpoint metadata is missing")
    else:
        for field, expected in (
            (
                "checkpoint_id",
                "WS-PROJECT-CONTINUATION-CHECKPOINT-20260725-004",
            ),
            ("version", "1.15.0"),
            ("as_of", "2026-07-25"),
            ("status", "ACTIVE_WORKING_CHECKPOINT"),
        ):
            _require_equal(
                errors,
                f"v2.4 prepared metadata {field}",
                metadata.get(field),
                expected,
            )
    return errors


def validate(
    root: Path,
    checkpoint_path: Path = V24_CHECKPOINT_RELATIVE,
    archive_path: Path = V23_ARCHIVE_RELATIVE,
    manifest_path: Path = V24_MANIFEST_RELATIVE,
    *,
    expected_prepared_sha256: str | None = None,
    expected_authorization_sha256: str | None = None,
) -> list[str]:
    errors, archive = validate_frozen_v23_boundary(root, archive_path)
    checkpoint_file = resolve_repo_file(root, checkpoint_path)
    if checkpoint_file is None:
        return errors + ["v2.4 checkpoint is missing"]
    try:
        checkpoint = load_json(checkpoint_file)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"v2.4 checkpoint cannot be loaded: {exc}"]
    errors.extend(
        validate_seq39_canonical_binding_authorization_request(
            root,
            checkpoint,
        )
    )
    errors.extend(
        validate_seq39_canonical_binding_update(
            root,
            checkpoint,
        )
    )
    if archive:
        finalized_prepared = (
            None
            if EXPECTED_V24_PREPARED_EVENT_SHA256.startswith("__FINALIZE_")
            else EXPECTED_V24_PREPARED_EVENT_SHA256
        )
        finalized_authorization = (
            None
            if EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256.startswith(
                "__FINALIZE_"
            )
            else EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
        )
        errors.extend(
            validate_prepared_checkpoint_projection(checkpoint, archive)
        )
        errors.extend(
            validate_transition_replay(
                root,
                checkpoint,
                archive,
                manifest_path,
                expected_prepared_sha256=(
                    expected_prepared_sha256
                    if expected_prepared_sha256 is not None
                    else finalized_prepared
                ),
                expected_authorization_sha256=(
                    expected_authorization_sha256
                    if expected_authorization_sha256 is not None
                    else finalized_authorization
                ),
            )
        )
    errors.extend(validate_working_snapshot(root, checkpoint))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--checkpoint", type=Path, default=V24_CHECKPOINT_RELATIVE)
    parser.add_argument("--archive", type=Path, default=V23_ARCHIVE_RELATIVE)
    parser.add_argument("--manifest", type=Path, default=V24_MANIFEST_RELATIVE)
    parser.add_argument("--print-working-snapshot-hashes", action="store_true")
    parser.add_argument("--print-gate-repository-state", action="store_true")
    parser.add_argument("--gate-event-id")
    args = parser.parse_args()
    root = args.root.resolve()
    checkpoint_path = (
        args.checkpoint
        if args.checkpoint.is_absolute()
        else root / args.checkpoint
    )
    if (
        args.print_working_snapshot_hashes
        and args.print_gate_repository_state
    ):
        print("choose exactly one print mode", file=sys.stderr)
        return 2
    if args.gate_event_id and not args.print_gate_repository_state:
        print(
            "--gate-event-id requires --print-gate-repository-state",
            file=sys.stderr,
        )
        return 2
    if args.print_gate_repository_state:
        if not args.gate_event_id:
            print("--gate-event-id is required", file=sys.stderr)
            return 2
        canonical_checkpoint = (root / V24_CHECKPOINT_RELATIVE).resolve()
        if checkpoint_path.resolve() != canonical_checkpoint:
            print(
                "--print-gate-repository-state requires the canonical "
                "v2.4 checkpoint path",
                file=sys.stderr,
            )
            return 2
        try:
            payload = capture_gate_repository_state(
                root,
                canonical_checkpoint,
                args.gate_event_id,
            )
        except (
            OSError,
            RuntimeError,
            ValueError,
            json.JSONDecodeError,
        ) as exc:
            print(f"gate repository state capture failed: {exc}", file=sys.stderr)
            return 2
        sys.stdout.buffer.write(canonical_json_bytes(payload) + b"\n")
        return 0
    if args.print_working_snapshot_hashes:
        try:
            checkpoint = load_json(checkpoint_path.resolve())
            snapshot = checkpoint["working_tree_snapshot"]
            paths = snapshot["managed_changed_paths"]
            if (
                not isinstance(paths, list)
                or not all(isinstance(path, str) for path in paths)
            ):
                raise ValueError("managed_changed_paths must be a string list")
            path_hash, content_hash = working_snapshot_hashes(root, paths)
        except (
            OSError,
            KeyError,
            RuntimeError,
            ValueError,
            json.JSONDecodeError,
        ) as exc:
            print(f"working snapshot calculation failed: {exc}", file=sys.stderr)
            return 2
        print(
            json.dumps(
                {
                    "base_commit": snapshot.get("base_head"),
                    "current_head": current_head(root),
                    "file_count": len(paths),
                    "path_set_sha256": path_hash,
                    "content_set_sha256": content_hash,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    errors = validate(
        root,
        args.checkpoint,
        args.archive,
        args.manifest,
    )
    if errors:
        print("WalkSafe v2.4 continuation check: FAIL", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("WalkSafe v2.4 continuation check: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
