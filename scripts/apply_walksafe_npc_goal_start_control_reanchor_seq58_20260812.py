#!/usr/bin/env python3
"""Project and atomically publish the zero-credit NPC start-control seq58.

The default ``--check`` mode is read-only.  ``--write`` remains available for
the later, separately authorized publication step; this module never runs it
implicitly.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810 as retained
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract


CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
GOAL_ID = TARGET_GOAL_ID
TARGET_GOAL_SHA256 = (
    "234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d"
)
TARGET_GOAL_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
    "epic-03-npc-single-admin-recovery-r001.md"
)
CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "NPC-20260812-001"
)
CONTROL_REANCHOR_SEQUENCE = 58
SOURCE_READY_SEQUENCE = 57
SOURCE_READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-NPC-SINGLE-ADMIN-RECOVERY-"
    "20260810-001"
)
SOURCE_READY_EVENT_SHA256 = (
    "08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef"
)
SOURCE_CHECKPOINT_FILE_SHA256 = (
    "de3ffafa2d8ff151beedc28e7b5f45296382dcdf93e41280f43136532e54358e"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_796_959
SOURCE_CHECKPOINT_SCHEMA_VERSION = "1.25.0"
STATIC_PLAN_MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R001"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
WORK_ITEM_ID = "EPIC-03-NPC-SINGLE-ADMIN-RECOVERY"
READY_FRONTIER = [TARGET_GOAL_ID, PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]

R001_CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/"
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/"
    "initial-start-gate-contract-r001.json"
)
R001_CONTRACT_FILE_SHA256 = (
    "0e9b80005e3ad206af6d43d724dfc70688e6d7ef8907ad6ee2c72da2e60679d1"
)
R001_CONTRACT_CANONICAL_SHA256 = (
    "b6a8ada662716ee963f1248ffdf5fdd730c545640a35a7fc11ed134016321186"
)
R002_CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/"
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/"
    "initial-start-gate-contract-r002.json"
)
R002_CONTRACT_FILE_SHA256 = (
    "37b843953a5c8089ae2b23224804fbef0c21150c2f2bbf876a57cb4cd3083227"
)
R002_CONTRACT_CANONICAL_SHA256 = (
    "1ca0369ac5be15375514d800b1c5a66f9a3ff3af4c487df6e379f57a54ca67de"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)

AUTHORIZATION_RELATIVE = Path(
    "docs/control/execution/goal-start-control-reanchors/"
    f"{CONTROL_REANCHOR_EVENT_ID}/authorization.md"
)
AUTHORIZATION_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-"
    "AUTHORIZATION-20260812-001"
)
AUTHORIZATION_FILE_SHA256 = (
    "1fe494dc36160fae2c6cd4ed344bd1515a23faad3f4f3c2004baf9375ec3e819"
)
AUTHORIZATION_BYTE_COUNT = 12_634
AUTHORIZATION_RECORDED_AT = "2026-08-12T22:30:24+09:00"
INDEPENDENT_REVIEW_RELATIVE = AUTHORIZATION_RELATIVE.with_name(
    "independent-review.md"
)
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-"
    "INDEPENDENT-REVIEW-20260812-001"
)
INDEPENDENT_REVIEW_FILE_SHA256 = (
    "3f7b4be0d4687001d2caf9e7c8a2524f928e45c960e60fd6dad0119e68557047"
)
INDEPENDENT_REVIEW_BYTE_COUNT = 14_182
INDEPENDENT_REVIEWED_AT = "2026-08-12T22:32:19+09:00"
CONTROL_REANCHOR_OCCURRED_AT = "2026-08-12T22:32:20+09:00"

SOURCE_REPOSITORY_CONTEXT = {
    "branch": "codex/walksafe-rc2-hardening-20260715",
    "base_commit": "a3ad7eead6b5d834d3e0675422475a9aad351e3d",
    "current_head": "a3ad7eead6b5d834d3e0675422475a9aad351e3d",
    "managed_changed_path_count": 818,
    "path_set_sha256": (
        "a92ca456869315d43939ffd3a46295ca8f405dc7acc3613384d08d70e4868f45"
    ),
    "content_set_sha256": (
        "7419a29ef7d1b4e1347c9111dde2b39e7abcdf3e7efb05ad28ee1f39df30242e"
    ),
}
AUTHORIZED_BRANCH = "current"
AUTHORIZED_BASE_COMMIT = "f0093863e82bfc80d9f11915cef33a51d44b8730"
AUTHORIZED_HEAD_COMMIT = "ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c"
GIT_AUTHORITY_PATHS = (".git/HEAD", ".git/refs/heads/current")
VALIDATION_CANDIDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
    "20260812-000"
)
VALIDATION_CANDIDATE_ROOT_RELATIVE = Path(
    "docs/control/execution/goal-gates"
) / VALIDATION_CANDIDATE_EVENT_ID

# This exact add-only path set is intentionally literal.  Missing paths make
# production preflight fail until the complete seq58 -> gate -> seq59 control
# cohort is stable.  Existing members of the sealed 818-path source snapshot
# are not repeated here.
SOURCE_PATHS = (
    "docs/catalogs/repository-paths.json",
    "docs/catalogs/scripts.json",
    "docs/catalogs/tests.json",
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001/initial-start-gate-contract-r002.json",
    "docs/control/execution/goal-start-control-reanchors/WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-20260812-001/authorization.md",
    "docs/control/execution/goal-start-control-reanchors/WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-20260812-001/independent-review.md",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/manifest.json",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/003b459f377d6f5ee04504aec4a6a3b5ec808ae3.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/0079a15b74261723cad65ad623d06c72fe9c778d.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/01777c65391c8ddb882b68aaa95605487b87cbc1.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/0e532328820dd6b63ff3770b7b240dacfdc5f3f1.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/15376ea25bf9b5cd816a27982a7cb8e46dd98f17.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/1dbf2d704adfbe859eb18937a241312690fd1810.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/238a43898aed2601acc12387b7d80625ea5d40d7.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/27c175ff57acaacad37d0766f8e1cfb482997d58.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/3707d98d74706e9e3d152cd2297104e5563cbe97.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/372f1e370f39bdee5cd1c777f0add32809a02759.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/3b0494b6a3fe0f6b01af834591d137eadb71183b.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/43f01268bc542d42b432f9a838566195722dd9b0.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/4ea563ec850298571cb11745d554ef961991acf1.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/54cb6ac5622ab88e63da1a06b3b01b5150603101.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/6587d8a91e24ca0468fcf8f7298fe9bd385b2a67.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/6686f010616948c55d1f36acee1844bc76bbe7d0.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/6875eaf118bbb7a460d5e5085e1c42276217955c.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/743d92ee9f65fdee544c692e0890f91b3481919c.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/757dcf39afafabca51a319de3d17253310e2861e.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/7a838d6abd6edfd5eb5d566679668c81b23813d7.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/8672efec3e3caf438da42bf0db762355211c594b.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/88c5e802fe377a718dc29712a05532981967e60e.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/898701bf3999703fd7472d9c24b282a58b6f23c0.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/9c2e3e2e1c5c61bcc15576e4eab4888a1412552c.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/9e53e7a10e3776da78fa7270066024c5ae14de70.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/a06158f5a79ce0e8a98fbf44e5b3da87fd62b2ed.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/a3ad7eead6b5d834d3e0675422475a9aad351e3d.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/b765761cfed06bc182145251eb67225235d16a59.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/b76720fc3af47133011da8bbf8fa1bf1cba2a578.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/b7f730dc76a6fc784015e5a5cd87fda9726cb28f.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/bade96d97d3e2a4ffa676518df3a3ca97ef58802.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/bba3b7650cfbe04e82f5b68cfb4be9c257185f43.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/c0400bc3068abc4a7615c0fe6f26aea184be8249.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/c1eaf73b5880dd875637923bf82a23fe14121f3f.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/c4adcc950cdc1aafe5f8789d943fb56e363f3ded.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/c506ab76cccbe317410b2b34d94ff0ab3e5debce.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/c5e23cee3a1315ac6ba5b452bb113e21dc347753.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/d0a87340f02fb0e76b77cdd441eb636e81c7199c.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/d331c239d6ecaee8c295bbb7a607b6e9757d0cff.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/de954af9278dfdbd6f648082feb63b8bfe886784.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/e1752d3b1366797ec33c0fa41ba6b9650f6d6a11.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/e8d7399df83a5136c06cc8ed11900b40bf2b24c1.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/f1d07c05b23dde1be90afc3faa905c0a2a093c26.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/f2c1188b98ad1faca8b77d7499a62d5f41009f60.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/f44f98f15c7297b1ee1aaf5b54e724603b7f4348.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/f77cad35e1229e9f3c03049a6ea9b9413efaa634.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/f84e5c6a79e86d8b9ed73cc2e70d497d418add07.gz.b64",
    "docs/control/history/git-witnesses/a3ad7ee-20260812/objects/fc902d399cf29f361482894512cb5716630cd7ba.gz.b64",
    "scripts/apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py",
    "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py",
    "scripts/build_walksafe_historical_git_witness_20260812.py",
    "scripts/generate_repository_catalogs.py",
    "scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py",
    "scripts/run_walksafe_test_layers_current.sh",
    "tests/test_apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py",
    "tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py",
    "tests/test_repository_catalogs.py",
    "tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py",
    "tests/test_walksafe_npc_single_admin_recovery_start_gate_contract_r002_20260812.py",
    "tests/test_walksafe_test_database_preflight.py",
)

EVENT_FIELDS = {
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
    "blockers_after",
    "blocker_resolution_ids_after",
    "source_checkpoint_version",
    "evidence_refs",
    "source_ready_event_binding",
    "contract_supersession",
    "repository_context_reanchor",
    "authorization_binding",
    "independent_review_binding",
    "claim_boundary",
    "unchanged_control_projection",
    "previous_event_sha256",
    "event_sha256",
}

CLAIM_BOUNDARY = {
    "implementation_start_authorized": False,
    "goal_status_change_count": 0,
    "product_implementation_credit_delta": 0,
    "artifact_completion_credit_delta": 0,
    "test_credit_delta": 0,
    "formal_test_credit_delta": 0,
    "approval_credit_delta": 0,
    "actual_event_credit_delta": 0,
    "external_action_credit_delta": 0,
    "actual_device_credit_delta": 0,
    "deployment_credit_delta": 0,
    "signing_credit_delta": 0,
    "release_credit_delta": 0,
    "final_completion_credit_delta": 0,
    "formal_test_not_run_count": 279,
    "remaining_gate_count": 5,
    "remaining_gates_waived": False,
    "release_status": "NOT_ELIGIBLE",
}

# Finalized below from the exact seq57 source using _control_projection_hashes.
SOURCE_UNCHANGED_CONTROL_SHA256 = {
    "artifact": "86f6ea0814924165caf6a79ed3b47ef0989806c463ae4696c3dfc42cecd21b2a",
    "canonical": "f3de1183e57bbeff58d758b5dfc076f7a80696e7001fce359ee14d0f6700b489",
    "completion": "6d40a80353384f59c6e9f10653a1a92e7c51e7b31135d97bb8779fd54a18d1d6",
    "current_work": "c07e8b352186720ed575c562c31655ff3c90586c058093c5276dd1f5cf6663d9",
    "runtime": "e0e959210e6523e7082e9b3e5bd7ed2fa21a8e07d396623a2995285d5e738bb8",
    "status": "e3d38b7859d6ebbf4fab2cd708e77510eb17b83d57035a64f7bcb5af05fb0283",
    "verification": "406f4ec4b66dd3672e1fc2246c5ec2ce4c1ea6fa388549e6dabb52e49f2ac11c",
}

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
TIMESTAMP_MARKER_RE = re.compile(
    r"^- (?:Recorded|Reviewed) at: `([^`]+)`$", re.MULTILINE
)


class ControlReanchorError(RuntimeError):
    """The exact seq58 projection cannot be proven or published."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ControlReanchorError(message)


def _json_bytes(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _canonical_sha256(value: Any) -> str:
    return contract.canonical_json_sha256(value)


def _safe_regular_file(
    root: Path,
    relative: Path | str,
    *,
    mode: int | None = None,
) -> Path:
    value = Path(relative)
    _require(
        not value.is_absolute()
        and value.parts
        and all(part not in {"", ".", ".."} for part in value.parts),
        f"unsafe repository path: {value}",
    )
    current = root.resolve(strict=True)
    for part in value.parts:
        current /= part
        _require(not current.is_symlink(), f"repository path contains a symlink: {value}")
    metadata = current.lstat()
    _require(
        stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1,
        f"repository path is not a single-link regular file: {value}",
    )
    if mode is not None:
        _require(
            stat.S_IMODE(metadata.st_mode) == mode,
            f"repository path mode differs: {value}",
        )
    return current


def _load_json_bytes(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ControlReanchorError(f"{label} is not valid JSON") from exc
    _require(isinstance(value, dict), f"{label} root is not an object")
    return value


def _previous_contract_binding() -> dict[str, str]:
    return {
        "document_id": (
            "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-"
            "20260810-001"
        ),
        "contract_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R001",
        "contract_version": "2026-08-10.1",
        "path": R001_CONTRACT_RELATIVE.as_posix(),
        "file_sha256": R001_CONTRACT_FILE_SHA256,
        "canonical_sha256": R001_CONTRACT_CANONICAL_SHA256,
    }


def _source_ready_event_binding() -> dict[str, Any]:
    return {
        "sequence": SOURCE_READY_SEQUENCE,
        "event_id": SOURCE_READY_EVENT_ID,
        "event_sha256": SOURCE_READY_EVENT_SHA256,
        "goal_id": TARGET_GOAL_ID,
        "status": "READY",
    }


def _source_repository_context_binding() -> dict[str, Any]:
    return {
        "checkpoint_path": CHECKPOINT_RELATIVE.as_posix(),
        "checkpoint_file_sha256": SOURCE_CHECKPOINT_FILE_SHA256,
        "checkpoint_byte_count": SOURCE_CHECKPOINT_BYTE_COUNT,
        **SOURCE_REPOSITORY_CONTEXT,
    }


def _replacement_contract_binding() -> dict[str, str]:
    return {
        "schema_version": "1.1",
        "document_id": (
            "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-"
            "20260812-002"
        ),
        "path": R002_CONTRACT_RELATIVE.as_posix(),
        "file_sha256": R002_CONTRACT_FILE_SHA256,
        "contract_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R002",
        "contract_version": "2026-08-12.1",
        "canonical_contract_sha256": R002_CONTRACT_CANONICAL_SHA256,
    }


def _authorization_binding() -> dict[str, Any]:
    return {
        "document_id": AUTHORIZATION_DOCUMENT_ID,
        "path": AUTHORIZATION_RELATIVE.as_posix(),
        "file_sha256": AUTHORIZATION_FILE_SHA256,
        "byte_count": AUTHORIZATION_BYTE_COUNT,
        "recorded_at": AUTHORIZATION_RECORDED_AT,
    }


def _independent_review_binding() -> dict[str, Any]:
    return {
        "document_id": INDEPENDENT_REVIEW_DOCUMENT_ID,
        "path": INDEPENDENT_REVIEW_RELATIVE.as_posix(),
        "file_sha256": INDEPENDENT_REVIEW_FILE_SHA256,
        "byte_count": INDEPENDENT_REVIEW_BYTE_COUNT,
        "reviewed_at": INDEPENDENT_REVIEWED_AT,
    }


def _load_contract_bindings(root: Path) -> tuple[dict[str, str], dict[str, str]]:
    r001_path = _safe_regular_file(root, R001_CONTRACT_RELATIVE)
    r001_raw = r001_path.read_bytes()
    r001 = _load_json_bytes(r001_raw, "R001 start-gate contract")
    _require(
        _sha256_bytes(r001_raw) == R001_CONTRACT_FILE_SHA256
        and _canonical_sha256(r001) == R001_CONTRACT_CANONICAL_SHA256,
        "R001 start-gate contract binding differs",
    )
    r002_path = _safe_regular_file(root, R002_CONTRACT_RELATIVE)
    r002_raw = r002_path.read_bytes()
    r002 = _load_json_bytes(r002_raw, "R002 start-gate contract")
    _require(
        _sha256_bytes(r002_raw) == R002_CONTRACT_FILE_SHA256
        and _canonical_sha256(r002) == R002_CONTRACT_CANONICAL_SHA256,
        "R002 start-gate contract binding differs",
    )
    checks = r002.get("ordered_checks")
    _require(
        isinstance(checks, list)
        and tuple(
            item.get("check_id") if isinstance(item, dict) else None
            for item in checks
        )
        == EXPECTED_CHECK_IDS,
        "R002 start-gate check order differs",
    )
    _require(
        r002.get("supersedes")
        == {
            **_previous_contract_binding(),
            "source_ready_event_sequence": SOURCE_READY_SEQUENCE,
            "source_ready_event_id": SOURCE_READY_EVENT_ID,
            "source_ready_event_sha256": SOURCE_READY_EVENT_SHA256,
        },
        "R002 predecessor contract binding differs",
    )
    return _previous_contract_binding(), _replacement_contract_binding()


def _load_markdown_bindings(root: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    specifications = (
        (
            AUTHORIZATION_RELATIVE,
            AUTHORIZATION_FILE_SHA256,
            AUTHORIZATION_BYTE_COUNT,
            AUTHORIZATION_RECORDED_AT,
            AUTHORIZATION_DOCUMENT_ID,
            "Authorization ID",
            _authorization_binding(),
        ),
        (
            INDEPENDENT_REVIEW_RELATIVE,
            INDEPENDENT_REVIEW_FILE_SHA256,
            INDEPENDENT_REVIEW_BYTE_COUNT,
            INDEPENDENT_REVIEWED_AT,
            INDEPENDENT_REVIEW_DOCUMENT_ID,
            "Review ID",
            _independent_review_binding(),
        ),
    )
    bindings: list[dict[str, Any]] = []
    for relative, digest, size, timestamp, document_id, id_label, binding in specifications:
        raw = _safe_regular_file(root, relative).read_bytes()
        _require(
            len(raw) == size and _sha256_bytes(raw) == digest,
            f"authority document binding differs: {relative}",
        )
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ControlReanchorError(
                f"authority document is not UTF-8: {relative}"
            ) from exc
        markers = TIMESTAMP_MARKER_RE.findall(text)
        _require(
            markers == [timestamp]
            and f"- {id_label}: `{document_id}`" in text
            and f"- Planned event ID: `{CONTROL_REANCHOR_EVENT_ID}`" in text
            and f"- Planned sequence: `{CONTROL_REANCHOR_SEQUENCE}`" in text,
            f"authority document identity differs: {relative}",
        )
        bindings.append(binding)
    _require(
        datetime.fromisoformat(CONTROL_REANCHOR_OCCURRED_AT)
        == datetime.fromisoformat(INDEPENDENT_REVIEWED_AT) + timedelta(seconds=1),
        "seq58 chronology differs",
    )
    return bindings[0], bindings[1]


def _runtime_projection(state: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state.get("focus_goal_id"),
        "focus_goal_path": state.get("focus_goal_path"),
        "focus_work_item_id": state.get("focus_work_item_id"),
        "focus_source": state.get("focus_source"),
        "ready_frontier_goal_ids": copy.deepcopy(state.get("ready_frontier_goal_ids")),
        "blocked_goal_ids": copy.deepcopy(state.get("blocked_goal_ids")),
        "pending_questions": copy.deepcopy(state.get("pending_questions")),
        "open_question_count": state.get("open_question_count"),
        "artifact_work_queue_sha256": _canonical_sha256(
            state.get("artifact_work_queue")
        ),
        "completion_boundary_sha256": _canonical_sha256(
            state.get("completion_boundary")
        ),
        "activation_status": state.get("activation_status"),
        "package_status": state.get("package_status"),
    }


def _control_projection_values(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    _require(isinstance(state, dict), "goal_execution is missing")
    return {
        "artifact": {
            "artifact_work_queue": copy.deepcopy(state.get("artifact_work_queue")),
            "artifact_state_counts": copy.deepcopy(
                checkpoint.get("approved_state", {}).get("artifact_state_counts")
            ),
        },
        "canonical": copy.deepcopy(checkpoint.get("canonical_bindings")),
        "completion": {
            "completion_boundary": copy.deepcopy(state.get("completion_boundary")),
            "completion_evidence_by_goal": copy.deepcopy(
                state.get("completion_evidence_by_goal")
            ),
            "archived_completion_evidence_by_goal": copy.deepcopy(
                state.get("archived_completion_evidence_by_goal")
            ),
            "pending_reopen_goal_ids": copy.deepcopy(
                state.get("pending_reopen_goal_ids")
            ),
            "pending_producer_completion_goal_id": state.get(
                "pending_producer_completion_goal_id"
            ),
        },
        "current_work": copy.deepcopy(checkpoint.get("current_work")),
        "runtime": {
            "runtime": _runtime_projection(state),
            "blockers_by_goal": copy.deepcopy(state.get("blockers_by_goal")),
            "blocker_resolution_history": copy.deepcopy(
                state.get("blocker_resolution_history")
            ),
            "dynamic_goal_inventory": copy.deepcopy(
                state.get("dynamic_goal_inventory")
            ),
            "materialized_child_goal_ids_by_parent": copy.deepcopy(
                state.get("materialized_child_goal_ids_by_parent")
            ),
        },
        "status": {
            "goal_status": state.get("goal_status"),
            "status_by_goal": copy.deepcopy(state.get("status_by_goal")),
        },
        "verification": {
            "approved_state": copy.deepcopy(checkpoint.get("approved_state")),
            "verification_boundary": copy.deepcopy(
                checkpoint.get("verification_boundary")
            ),
        },
    }


def _control_projection_hashes(checkpoint: Mapping[str, Any]) -> dict[str, str]:
    return {
        name: _canonical_sha256(value)
        for name, value in _control_projection_values(checkpoint).items()
    }


def _unchanged_projection(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    hashes = _control_projection_hashes(checkpoint)
    return {
        name: {
            "before_sha256": digest,
            "after_sha256": digest,
        }
        for name, digest in sorted(hashes.items())
    }


def _validate_source_history(history: list[Any]) -> dict[str, Any]:
    _require(len(history) == SOURCE_READY_SEQUENCE, "source checkpoint is not seq57")
    previous = ""
    for sequence, event in enumerate(history, start=1):
        _require(isinstance(event, dict), f"source event {sequence} is not an object")
        _require(
            event.get("sequence") == sequence
            and event.get("previous_event_sha256") == previous
            and event.get("event_sha256") == contract.event_sha256(event),
            f"source event {sequence} chain differs",
        )
        previous = event["event_sha256"]
    ready = history[-1]
    _require(
        ready.get("event_id") == SOURCE_READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == TARGET_GOAL_ID
        and ready.get("from_status") == "PLANNED"
        and ready.get("to_status") == "READY"
        and ready.get("event_sha256") == SOURCE_READY_EVENT_SHA256
        and ready.get("implementation_start_gate_contract_binding")
        == _previous_contract_binding(),
        "source seq57 READY event differs",
    )
    return ready


def require_exact_source(checkpoint: Mapping[str, Any]) -> None:
    _require(
        checkpoint.get("schema_version") == SOURCE_CHECKPOINT_SCHEMA_VERSION,
        "source checkpoint schema differs",
    )
    state = checkpoint.get("goal_execution")
    _require(isinstance(state, dict), "source goal_execution is missing")
    history = state.get("transition_history")
    _require(isinstance(history, list), "source transition history is missing")
    ready = _validate_source_history(history)
    _require(
        state.get("transition_history_anchor_sha256") == SOURCE_READY_EVENT_SHA256
        and state.get("validation_cutoff_at") == ready.get("occurred_at")
        and state.get("package_status") == "ACTIVE"
        and state.get("activation_status") == "ACTIVE"
        and state.get("focus_goal_id") == TARGET_GOAL_ID
        and state.get("focus_goal_path") == TARGET_GOAL_RELATIVE.as_posix()
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("focus_source") == "IMPLEMENTATION_BACKLOG"
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER,
        "source runtime focus differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        isinstance(statuses, dict)
        and statuses.get(TARGET_GOAL_ID) == "READY"
        and statuses.get(PREDECESSOR_GOAL_ID) == "COMPLETE_AT_TARGET"
        and "IN_PROGRESS" not in statuses.values(),
        "source Goal status boundary differs",
    )
    _require(
        state.get("blocked_goal_ids") == []
        and state.get("blockers_by_goal") == {}
        and state.get("pending_questions") == []
        and state.get("open_question_count") == 0
        and state.get("pending_producer_completion_goal_id") in {None, ""},
        "source blocker or question boundary differs",
    )
    snapshot = checkpoint.get("working_tree_snapshot")
    repository = checkpoint.get("repository")
    handoff = checkpoint.get("session_handoff")
    _require(
        isinstance(snapshot, dict)
        and isinstance(repository, dict)
        and isinstance(handoff, dict),
        "source repository snapshot is missing",
    )
    source_handoff = handoff.get("source_commit_or_snapshot")
    _require(
        isinstance(source_handoff, dict)
        and repository.get("branch") == SOURCE_REPOSITORY_CONTEXT["branch"]
        and repository.get("snapshot_base_head")
        == SOURCE_REPOSITORY_CONTEXT["base_commit"]
        and snapshot.get("base_head") == SOURCE_REPOSITORY_CONTEXT["base_commit"]
        and snapshot.get("managed_changed_path_count")
        == SOURCE_REPOSITORY_CONTEXT["managed_changed_path_count"]
        and snapshot.get("path_set_sha256")
        == SOURCE_REPOSITORY_CONTEXT["path_set_sha256"]
        and snapshot.get("content_set_sha256")
        == SOURCE_REPOSITORY_CONTEXT["content_set_sha256"]
        and handoff.get("branch") == SOURCE_REPOSITORY_CONTEXT["branch"]
        and source_handoff.get("base_commit")
        == SOURCE_REPOSITORY_CONTEXT["base_commit"]
        and source_handoff.get("current_head")
        == SOURCE_REPOSITORY_CONTEXT["current_head"],
        "source repository context differs",
    )
    source_paths = snapshot.get("managed_changed_paths")
    _require(
        isinstance(source_paths, list)
        and source_paths == sorted(set(source_paths))
        and len(source_paths) == SOURCE_REPOSITORY_CONTEXT["managed_changed_path_count"]
        and hashlib.sha256(
            ("\n".join(source_paths) + "\n").encode("utf-8")
        ).hexdigest()
        == SOURCE_REPOSITORY_CONTEXT["path_set_sha256"]
        and not set(SOURCE_PATHS).intersection(source_paths),
        "source managed path authority differs",
    )
    _require(
        _control_projection_hashes(checkpoint)
        == SOURCE_UNCHANGED_CONTROL_SHA256,
        "source unchanged-control projection differs",
    )


def _git_output(root: Path, arguments: list[str]) -> str:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    _require(
        completed.returncode == 0,
        f"Git command failed: {' '.join(arguments)}",
    )
    return completed.stdout.strip()


def _git_nul_paths(root: Path, arguments: Sequence[str], label: str) -> list[str]:
    completed = subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    _require(completed.returncode == 0, f"Git {label} capture failed")
    paths: list[str] = []
    for raw in completed.stdout.split(b"\0"):
        if not raw:
            continue
        try:
            path = raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ControlReanchorError(f"Git {label} path is not UTF-8") from exc
        value = Path(path)
        _require(
            not value.is_absolute()
            and value.parts
            and all(part not in {"", ".", ".."} for part in value.parts),
            f"unsafe Git {label} path: {path}",
        )
        paths.append(path)
    _require(len(paths) == len(set(paths)), f"Git {label} path set is not unique")
    return sorted(paths)


def _derive_live_managed_paths(
    root: Path,
    *,
    ignored_paths: Sequence[str] = (),
) -> list[str]:
    tracked = _git_nul_paths(
        root,
        ["diff", "--name-only", "-z", AUTHORIZED_BASE_COMMIT, "--"],
        "tracked base-diff",
    )
    untracked = _git_nul_paths(
        root,
        ["ls-files", "--others", "--exclude-standard", "-z"],
        "untracked",
    )
    excluded_checkpoint = CHECKPOINT_RELATIVE.as_posix()
    ignored = set(ignored_paths)
    managed = sorted(
        path
        for path in set(tracked) | set(untracked)
        if path != excluded_checkpoint
        and not path.startswith("docs/control/execution/goal-gates/")
        and path not in ignored
    )
    _require(
        excluded_checkpoint in set(tracked) | set(untracked),
        "live checkpoint is not present in the authorized-base diff",
    )
    _require(
        set(SOURCE_PATHS).issubset(managed),
        "seq58 control input closure is missing from the live managed set",
    )
    return managed


def _require_live_repository(root: Path) -> None:
    _require(
        _git_output(root, ["rev-parse", "--show-toplevel"])
        == str(root.resolve(strict=True)),
        "repository root differs",
    )
    _require(
        _git_output(root, ["branch", "--show-current"]) == AUTHORIZED_BRANCH,
        "authorized branch differs",
    )
    _require(
        _git_output(root, ["rev-parse", "HEAD^{commit}"])
        == AUTHORIZED_HEAD_COMMIT,
        "authorized HEAD differs",
    )
    _require(
        _git_output(root, ["rev-parse", f"{AUTHORIZED_BASE_COMMIT}^{{commit}}"])
        == AUTHORIZED_BASE_COMMIT,
        "authorized base commit differs",
    )
    ancestor = subprocess.run(
        [
            "git",
            "merge-base",
            "--is-ancestor",
            AUTHORIZED_BASE_COMMIT,
            AUTHORIZED_HEAD_COMMIT,
        ],
        cwd=root,
        check=False,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    _require(ancestor.returncode == 0, "authorized base is not an ancestor of HEAD")


def _require_live_managed_membership(
    root: Path,
    managed_paths: Sequence[str],
    *,
    ignored_paths: Sequence[str] = (),
) -> None:
    live_paths = _derive_live_managed_paths(root, ignored_paths=ignored_paths)
    _require(
        set(live_paths).issubset(managed_paths)
        and set(SOURCE_PATHS).issubset(managed_paths),
        "authorized-base live changes escape the controlled managed snapshot",
    )


def _after_repository_context(
    *,
    managed_changed_path_count: int,
    path_set_sha256: str,
    content_set_sha256: str,
) -> dict[str, Any]:
    return {
        "branch": AUTHORIZED_BRANCH,
        "base_commit": AUTHORIZED_BASE_COMMIT,
        "current_head": AUTHORIZED_HEAD_COMMIT,
        "managed_changed_path_count": managed_changed_path_count,
        "path_set_sha256": path_set_sha256,
        "content_set_sha256": content_set_sha256,
    }


def project_seq58(
    source: Mapping[str, Any],
    *,
    managed_paths: list[str],
    path_set_sha256: str,
    content_set_sha256: str,
    previous_contract_binding: Mapping[str, Any],
    replacement_contract_binding: Mapping[str, Any],
    authorization_binding: Mapping[str, Any],
    independent_review_binding: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    require_exact_source(source)
    _require(
        managed_paths == sorted(set(managed_paths)),
        "projected managed paths are not sorted and unique",
    )
    after_context = _after_repository_context(
        managed_changed_path_count=len(managed_paths),
        path_set_sha256=path_set_sha256,
        content_set_sha256=content_set_sha256,
    )
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    ready = state["transition_history"][-1]
    event: dict[str, Any] = {
        "sequence": CONTROL_REANCHOR_SEQUENCE,
        "event_id": CONTROL_REANCHOR_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": datetime.fromisoformat(
            CONTROL_REANCHOR_OCCURRED_AT
        ).date().isoformat(),
        "occurred_at": CONTROL_REANCHOR_OCCURRED_AT,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "static_plan_manifest_sha256": STATIC_PLAN_MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": copy.deepcopy(ready["runtime_after"]),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": copy.deepcopy(
            ready["blocker_resolution_ids_after"]
        ),
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [
            "GOAL_START_CONTROL_REANCHOR_AUTHORIZATION",
            "GOAL_START_CONTROL_REANCHOR_INDEPENDENT_REVIEW",
            "INITIAL_START_GATE_CONTRACT_SUCCESSOR",
        ],
        "source_ready_event_binding": _source_ready_event_binding(),
        "contract_supersession": {
            "previous_contract_binding": copy.deepcopy(
                dict(previous_contract_binding)
            ),
            "replacement_contract_binding": copy.deepcopy(
                dict(replacement_contract_binding)
            ),
            "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
        },
        "repository_context_reanchor": {
            "before": _source_repository_context_binding(),
            "after": copy.deepcopy(after_context),
        },
        "authorization_binding": copy.deepcopy(dict(authorization_binding)),
        "independent_review_binding": copy.deepcopy(
            dict(independent_review_binding)
        ),
        "claim_boundary": copy.deepcopy(CLAIM_BOUNDARY),
        "unchanged_control_projection": _unchanged_projection(source),
        "previous_event_sha256": SOURCE_READY_EVENT_SHA256,
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == EVENT_FIELDS, "seq58 event field set differs")

    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = CONTROL_REANCHOR_OCCURRED_AT

    repository = checkpoint["repository"]
    repository["branch"] = AUTHORIZED_BRANCH
    repository["snapshot_base_head"] = AUTHORIZED_BASE_COMMIT

    snapshot = checkpoint["working_tree_snapshot"]
    snapshot["base_head"] = AUTHORIZED_BASE_COMMIT
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    snapshot["path_set_sha256"] = path_set_sha256
    snapshot["content_set_sha256"] = content_set_sha256
    snapshot["scope"] = (
        "Graph v2.4 NPC-SINGLE-ADMIN-RECOVERY/GAP-008 READY through the "
        "zero-credit Goal start-control reanchor seq58; R002 is the active "
        "start-gate successor and no GOAL_STARTED, product, artifact, test, "
        "formal, device, external, deployment, signing, or release credit is "
        "granted."
    )

    handoff = checkpoint["session_handoff"]
    handoff["branch"] = AUTHORIZED_BRANCH
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    source_snapshot = handoff["source_commit_or_snapshot"]
    source_snapshot["base_commit"] = AUTHORIZED_BASE_COMMIT
    source_snapshot["current_head"] = AUTHORIZED_HEAD_COMMIT
    source_snapshot["file_count"] = len(managed_paths)
    source_snapshot["path_set_sha256"] = path_set_sha256
    source_snapshot["content_set_sha256"] = content_set_sha256

    _require(
        _control_projection_hashes(checkpoint)
        == SOURCE_UNCHANGED_CONTROL_SHA256,
        "seq58 changed an immutable control projection",
    )
    return checkpoint, event


def _validate_projected_structure(checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    state = checkpoint.get("goal_execution")
    _require(isinstance(state, dict), "seq58 goal_execution is missing")
    history = state.get("transition_history")
    _require(
        isinstance(history, list) and len(history) == CONTROL_REANCHOR_SEQUENCE,
        "checkpoint is not the exact seq58 projection",
    )
    _validate_source_history(history[:-1])
    event = history[-1]
    _require(isinstance(event, dict), "seq58 event is not an object")
    _require(set(event) == EVENT_FIELDS, "seq58 event field set differs")
    _require(
        event.get("sequence") == CONTROL_REANCHOR_SEQUENCE
        and event.get("event_id") == CONTROL_REANCHOR_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("occurred_on") == "2026-08-12"
        and event.get("occurred_at") == CONTROL_REANCHOR_OCCURRED_AT
        and event.get("previous_focus_goal_id") == TARGET_GOAL_ID
        and event.get("previous_focus_content_sha256") == TARGET_GOAL_SHA256
        and event.get("focus_goal_id") == TARGET_GOAL_ID
        and event.get("focus_goal_content_sha256") == TARGET_GOAL_SHA256
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("from_status") == "READY"
        and event.get("to_status") == "READY"
        and event.get("static_plan_manifest_sha256")
        == STATIC_PLAN_MANIFEST_SHA256
        and event.get("status_changes") == {}
        and event.get("runtime_after") == _runtime_projection(state)
        and event.get("blockers_after") == state.get("blockers_by_goal")
        and event.get("blocker_resolution_ids_after") == []
        and event.get("source_checkpoint_version")
        == SOURCE_CHECKPOINT_SCHEMA_VERSION
        and event.get("previous_event_sha256") == SOURCE_READY_EVENT_SHA256
        and event.get("event_sha256") == contract.event_sha256(event),
        "seq58 event identity or invariant differs",
    )
    _require(
        event.get("source_ready_event_binding") == _source_ready_event_binding(),
        "seq58 source READY binding differs",
    )
    _require(
        event.get("contract_supersession")
        == {
            "previous_contract_binding": _previous_contract_binding(),
            "replacement_contract_binding": _replacement_contract_binding(),
            "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
        },
        "seq58 contract supersession differs",
    )
    _require(
        event.get("authorization_binding") == _authorization_binding()
        and event.get("independent_review_binding")
        == _independent_review_binding(),
        "seq58 authority bindings differ",
    )
    _require(
        event.get("claim_boundary") == CLAIM_BOUNDARY,
        "seq58 claim boundary differs",
    )
    expected_unchanged = {
        name: {
            "before_sha256": digest,
            "after_sha256": digest,
        }
        for name, digest in sorted(SOURCE_UNCHANGED_CONTROL_SHA256.items())
    }
    _require(
        event.get("unchanged_control_projection") == expected_unchanged
        and _control_projection_hashes(checkpoint)
        == SOURCE_UNCHANGED_CONTROL_SHA256,
        "seq58 unchanged-control projection differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == event.get("event_sha256")
        and state.get("validation_cutoff_at") == CONTROL_REANCHOR_OCCURRED_AT
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "READY"
        and state.get("status_by_goal", {}).get(PREDECESSOR_GOAL_ID)
        == "COMPLETE_AT_TARGET"
        and "IN_PROGRESS" not in state.get("status_by_goal", {}).values()
        and state.get("focus_goal_id") == TARGET_GOAL_ID
        and state.get("focus_goal_path") == TARGET_GOAL_RELATIVE.as_posix()
        and state.get("focus_work_item_id") == WORK_ITEM_ID
        and state.get("ready_frontier_goal_ids") == READY_FRONTIER,
        "seq58 runtime state differs",
    )
    return event


def require_control_reanchored_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    run_external_validators: bool = True,
    ignored_live_paths: Sequence[str] = (),
) -> None:
    root = root.resolve(strict=True)
    event = _validate_projected_structure(checkpoint)
    previous_contract, replacement_contract = _load_contract_bindings(root)
    authorization, review = _load_markdown_bindings(root)
    _require(
        event["contract_supersession"]["previous_contract_binding"]
        == previous_contract
        and event["contract_supersession"]["replacement_contract_binding"]
        == replacement_contract
        and event["authorization_binding"] == authorization
        and event["independent_review_binding"] == review,
        "seq58 live control input binding differs",
    )
    _require_live_repository(root)

    repository = checkpoint.get("repository")
    snapshot = checkpoint.get("working_tree_snapshot")
    handoff = checkpoint.get("session_handoff")
    _require(
        isinstance(repository, dict)
        and isinstance(snapshot, dict)
        and isinstance(handoff, dict),
        "seq58 repository projection is missing",
    )
    source_snapshot = handoff.get("source_commit_or_snapshot")
    paths = snapshot.get("managed_changed_paths")
    _require(
        isinstance(source_snapshot, dict)
        and isinstance(paths, list)
        and paths == sorted(set(paths)),
        "seq58 handoff or managed path projection differs",
    )
    additions = set(SOURCE_PATHS)
    _require(
        len(SOURCE_PATHS) == 67
        and tuple(sorted(SOURCE_PATHS)) == SOURCE_PATHS
        and len(additions) == len(SOURCE_PATHS)
        and additions.issubset(paths),
        "seq58 exact add-only source path set differs",
    )
    _require(
        CHECKPOINT_RELATIVE.as_posix() not in paths
        and not any(
            path.startswith("docs/control/execution/goal-gates/") for path in paths
        ),
        "seq58 managed path exclusion differs",
    )
    _require_live_managed_membership(
        root,
        paths,
        ignored_paths=ignored_live_paths,
    )
    path_sha256, content_sha256 = contract.working_snapshot_hashes(root, paths)
    after_context = _after_repository_context(
        managed_changed_path_count=len(paths),
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
    )
    _require(
        event.get("repository_context_reanchor")
        == {
            "before": _source_repository_context_binding(),
            "after": after_context,
        }
        and repository.get("branch") == AUTHORIZED_BRANCH
        and repository.get("snapshot_base_head") == AUTHORIZED_BASE_COMMIT
        and snapshot.get("base_head") == AUTHORIZED_BASE_COMMIT
        and snapshot.get("managed_changed_path_count") == len(paths)
        and snapshot.get("path_set_sha256") == path_sha256
        and snapshot.get("content_set_sha256") == content_sha256
        and handoff.get("branch") == AUTHORIZED_BRANCH
        and handoff.get("changed_files") == paths
        and source_snapshot.get("base_commit") == AUTHORIZED_BASE_COMMIT
        and source_snapshot.get("current_head") == AUTHORIZED_HEAD_COMMIT
        and source_snapshot.get("file_count") == len(paths)
        and source_snapshot.get("path_set_sha256") == path_sha256
        and source_snapshot.get("content_set_sha256") == content_sha256,
        "seq58 repository context or handoff parity differs",
    )
    if run_external_validators:
        errors, archive = contract.validate_frozen_v23_boundary(
            root, contract.V23_ARCHIVE_RELATIVE
        )
        errors.extend(
            contract.validate_seq39_canonical_binding_authorization_request(
                root, dict(checkpoint)
            )
        )
        errors.extend(
            contract.validate_seq39_canonical_binding_update(
                root, dict(checkpoint)
            )
        )
        if archive:
            errors.extend(
                contract.validate_transition_replay(
                    root,
                    dict(checkpoint),
                    archive,
                    contract.V24_MANIFEST_RELATIVE,
                    expected_prepared_sha256=(
                        contract.EXPECTED_V24_PREPARED_EVENT_SHA256
                    ),
                    expected_authorization_sha256=(
                        contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
                    ),
                )
            )
        errors.extend(contract.validate_working_snapshot(root, dict(checkpoint)))
        errors.extend(
            goal_graph.validate_v24_artifact_work_queue(root, dict(checkpoint))
        )
        _require(
            not errors,
            "seq58 external validation failed: " + "; ".join(errors),
        )


def _validate_candidate_with_public_validators(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> None:
    candidate_bytes = _json_bytes(checkpoint)
    candidate_document = _load_json_bytes(candidate_bytes, "seq58 candidate checkpoint")
    _require(candidate_document == checkpoint, "seq58 candidate JSON round trip differs")
    require_control_reanchored_checkpoint(
        root,
        candidate_document,
        run_external_validators=False,
    )
    _safe_regular_file(root, CHECKPOINT_RELATIVE, mode=0o600)
    candidate_parent = root / VALIDATION_CANDIDATE_ROOT_RELATIVE.parent
    _require(
        candidate_parent.resolve(strict=True) == candidate_parent
        and stat.S_ISDIR(candidate_parent.lstat().st_mode)
        and not candidate_parent.is_symlink(),
        "seq58 candidate parent authority differs",
    )
    candidate_directory = root / VALIDATION_CANDIDATE_ROOT_RELATIVE
    _require(
        not candidate_directory.exists() and not candidate_directory.is_symlink(),
        "seq58 candidate transaction directory already exists",
    )
    candidate_directory.mkdir(mode=0o700)
    candidate_path: Path | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(
            prefix="checkpoint-candidate-",
            suffix=".json",
            dir=candidate_directory,
        )
        candidate_path = Path(temporary_name)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(candidate_bytes)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(candidate_path, 0o600)
        candidate_relative = candidate_path.relative_to(root)
        errors = contract.validate(
            root,
            candidate_relative,
            contract.V23_ARCHIVE_RELATIVE,
            contract.V24_MANIFEST_RELATIVE,
        )
        errors.extend(
            goal_graph.validate(
                root,
                candidate_relative,
                contract.V23_ARCHIVE_RELATIVE,
                contract.V24_MANIFEST_RELATIVE,
                check_continuation=False,
            )
        )
        _require(
            not errors,
            "seq58 public candidate validation failed: " + "; ".join(errors),
        )
    finally:
        try:
            if candidate_path is not None:
                try:
                    metadata = candidate_path.lstat()
                except FileNotFoundError:
                    pass
                else:
                    _require(
                        stat.S_ISREG(metadata.st_mode)
                        and not candidate_path.is_symlink()
                        and metadata.st_nlink == 1,
                        "seq58 candidate cleanup authority differs",
                    )
                    candidate_path.unlink()
        finally:
            candidate_directory.rmdir()


@dataclass(frozen=True)
class SourceCheckpoint:
    raw: bytes
    document: dict[str, Any]


@dataclass
class PreparedProjection:
    root: Path
    checkpoint_path: Path
    source: SourceCheckpoint
    projected_checkpoint: dict[str, Any]
    projected_checkpoint_bytes: bytes
    event: dict[str, Any]
    cohort: retained.PinnedCohort
    git_authority: retained.PinnedCohort


def _load_source_checkpoint(root: Path) -> tuple[Path, SourceCheckpoint]:
    path = _safe_regular_file(root, CHECKPOINT_RELATIVE, mode=0o600)
    raw = path.read_bytes()
    _require(
        len(raw) == SOURCE_CHECKPOINT_BYTE_COUNT
        and _sha256_bytes(raw) == SOURCE_CHECKPOINT_FILE_SHA256,
        "source checkpoint physical binding differs",
    )
    document = _load_json_bytes(raw, "source checkpoint")
    require_exact_source(document)
    return path, SourceCheckpoint(raw=raw, document=document)


def prepare_projection(
    root: Path,
    *,
    run_external_validators: bool = True,
) -> PreparedProjection:
    root = root.resolve(strict=True)
    _require_live_repository(root)
    checkpoint_path, source = _load_source_checkpoint(root)
    previous_contract, replacement_contract = _load_contract_bindings(root)
    authorization, review = _load_markdown_bindings(root)
    source_paths = source.document["working_tree_snapshot"]["managed_changed_paths"]
    _require(
        tuple(sorted(SOURCE_PATHS)) == SOURCE_PATHS
        and not set(source_paths).intersection(SOURCE_PATHS),
        "seq58 source control-input closure is not add-only",
    )
    required_paths = set(contract.EXPECTED_CONTROLLED_PATHS) | set(
        contract.expected_goal_paths(source.document["goal_execution"])
    )
    live_paths = set(_derive_live_managed_paths(root))
    managed_paths = sorted(required_paths | live_paths)
    _require(
        required_paths.issubset(managed_paths)
        and live_paths.issubset(managed_paths)
        and set(SOURCE_PATHS).issubset(live_paths),
        "seq58 required, live, or control-input path escaped the projected manifest",
    )
    _require_live_managed_membership(root, managed_paths)
    path_sha256, content_sha256 = contract.working_snapshot_hashes(
        root, managed_paths
    )
    projected, event = project_seq58(
        source.document,
        managed_paths=managed_paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        previous_contract_binding=previous_contract,
        replacement_contract_binding=replacement_contract,
        authorization_binding=authorization,
        independent_review_binding=review,
    )
    cohort = retained.PinnedCohort.capture(root, managed_paths)
    try:
        git_authority = retained.PinnedCohort.capture(
            root,
            list(GIT_AUTHORITY_PATHS),
        )
    except BaseException as exc:
        cohort.close(exc)
        raise
    try:
        _require(
            cohort.content_set_sha256() == content_sha256,
            "retained managed content authority differs",
        )
        require_control_reanchored_checkpoint(
            root,
            projected,
            run_external_validators=False,
        )
        if run_external_validators:
            _validate_candidate_with_public_validators(root, projected)
        cohort.verify()
        git_authority.verify()
        _require_live_repository(root)
        _require(
            checkpoint_path.read_bytes() == source.raw,
            "source checkpoint changed during seq58 preparation",
        )
        return PreparedProjection(
            root=root,
            checkpoint_path=checkpoint_path,
            source=source,
            projected_checkpoint=projected,
            projected_checkpoint_bytes=_json_bytes(projected),
            event=event,
            cohort=cohort,
            git_authority=git_authority,
        )
    except BaseException as exc:
        try:
            cohort.close(exc)
        finally:
            git_authority.close(exc)
        raise


def write_projection(
    prepared: PreparedProjection,
    *,
    atomic_writer: Callable[..., None] = atomic.atomic_write,
) -> None:
    primary: BaseException | None = None

    def atomic_staging_paths() -> tuple[str, ...]:
        prefix = f".{prepared.checkpoint_path.name}.fp048-seq43-44."
        suffix = ".tmp"
        matches: list[str] = []
        for entry in prepared.checkpoint_path.parent.iterdir():
            name = entry.name
            token = (
                name[len(prefix):-len(suffix)]
                if name.startswith(prefix) and name.endswith(suffix)
                else ""
            )
            if not token:
                continue
            _require(
                re.fullmatch(r"[0-9a-f]{24}", token) is not None,
                "checkpoint transaction staging name differs",
            )
            metadata = entry.lstat()
            content = entry.read_bytes()
            _require(
                stat.S_ISREG(metadata.st_mode)
                and not entry.is_symlink()
                and stat.S_IMODE(metadata.st_mode) == 0o600
                and metadata.st_uid == os.geteuid()
                and metadata.st_nlink == 1
                and content
                in {
                    prepared.source.raw,
                    prepared.projected_checkpoint_bytes,
                },
                "checkpoint transaction staging authority differs",
            )
            matches.append(entry.relative_to(prepared.root).as_posix())
        _require(
            len(matches) <= 1,
            "multiple checkpoint transaction staging entries exist",
        )
        return tuple(matches)

    def transaction_guard() -> None:
        current = prepared.checkpoint_path.read_bytes()
        _require(
            current
            in {
                prepared.source.raw,
                prepared.projected_checkpoint_bytes,
            },
            "checkpoint changed at the seq58 transaction boundary",
        )
        prepared.cohort.verify()
        prepared.git_authority.verify()
        _require_live_repository(prepared.root)
        _load_contract_bindings(prepared.root)
        _load_markdown_bindings(prepared.root)
        require_control_reanchored_checkpoint(
            prepared.root,
            prepared.projected_checkpoint,
            run_external_validators=False,
            ignored_live_paths=atomic_staging_paths(),
        )
        _require(
            _json_bytes(prepared.projected_checkpoint)
            == prepared.projected_checkpoint_bytes,
            "seq58 candidate document/bytes diverged",
        )
        published_candidate = _load_json_bytes(
            prepared.projected_checkpoint_bytes,
            "seq58 candidate bytes",
        )
        _require(
            published_candidate == prepared.projected_checkpoint,
            "seq58 candidate bytes/document differ",
        )

    try:
        transaction_guard()
        atomic_writer(
            prepared.checkpoint_path,
            prepared.projected_checkpoint_bytes,
            expected_source=prepared.source.raw,
            commit_guard=transaction_guard,
        )
        metadata = prepared.checkpoint_path.lstat()
        _require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_nlink == 1
            and prepared.checkpoint_path.read_bytes()
            == prepared.projected_checkpoint_bytes,
            "published seq58 checkpoint authority differs",
        )
        published = _load_json_bytes(
            prepared.checkpoint_path.read_bytes(),
            "published seq58 checkpoint",
        )
        _require(
            published == prepared.projected_checkpoint,
            "published seq58 checkpoint semantics differ",
        )
        transaction_guard()
    except BaseException as exc:
        primary = exc
        raise
    finally:
        try:
            prepared.cohort.close(primary)
        finally:
            prepared.git_authority.close(primary)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    prepared: PreparedProjection | None = None
    try:
        prepared = prepare_projection(args.root)
        if args.write:
            write_projection(prepared)
        else:
            try:
                prepared.cohort.close()
            finally:
                prepared.git_authority.close()
    except (
        ControlReanchorError,
        retained.PublicationError,
        atomic.CompletionApplyError,
        atomic.CompletionPostCommitError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"NPC Goal start-control reanchor seq58: FAIL: {exc}", file=sys.stderr)
        return 1
    mode = "WRITE" if args.write else "CHECK"
    print(
        "NPC Goal start-control reanchor seq58: PASS "
        f"mode={mode} event_sha256={prepared.event['event_sha256']} "
        f"occurred_at={prepared.event['occurred_at']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
