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
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


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


def _load_frozen_v23_utility(
    root: Path = ROOT,
    *,
    relative: str = "scripts/check_walksafe_project_continuation_v2_3.py",
    module_name: str = "_walksafe_v23_continuation_utility_for_v24",
):
    path = root / relative
    expected = V23_FROZEN_FILE_SHA256[relative]
    if (
        not path.is_file()
        or hashlib.sha256(path.read_bytes()).hexdigest() != expected
    ):
        raise RuntimeError("frozen v2.3 continuation utility SHA-256 differs")
    spec = importlib.util.spec_from_file_location(
        module_name,
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
def capture_gate_repository_state(
    root: Path,
    checkpoint_path: Path,
    gate_event_id: str,
    *,
    ephemeral_exact_exclusion: str | None = None,
    controlled_snapshot_transitions: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    if (
        ephemeral_exact_exclusion is None
        and controlled_snapshot_transitions is None
    ):
        return _v23_utility.capture_gate_repository_state(
            root,
            checkpoint_path,
            gate_event_id,
        )
    utility = _load_frozen_v23_utility()
    original_exclusion = utility._gate_exclusion_kind

    def exclusion(relative: str, event_id: str) -> str | None:
        if relative == ephemeral_exact_exclusion:
            return "EPHEMERAL_EXACT_PATH"
        return original_exclusion(relative, event_id)

    if ephemeral_exact_exclusion is not None:
        utility._gate_exclusion_kind = exclusion
    transition_rows: tuple[dict[str, Any], ...] = ()
    initial_phase: int | None = None
    if controlled_snapshot_transitions is not None:
        transition_rows = tuple(copy.deepcopy(dict(row)) for row in controlled_snapshot_transitions)
        if not transition_rows:
            raise ValueError("controlled snapshot transitions are empty")
        paths = tuple(row.get("path") for row in transition_rows)
        if (
            not all(isinstance(path, str) and path for path in paths)
            or len(set(paths)) != len(paths)
            or not all(
                set(row) == {"path", "predecessor_worktree", "candidate_worktree"}
                and isinstance(row.get("predecessor_worktree"), dict)
                and isinstance(row.get("candidate_worktree"), dict)
                for row in transition_rows
            )
        ):
            raise ValueError("controlled snapshot transitions are invalid")

        def transition_phase(identities: Mapping[str, Any]) -> int:
            states: list[str] = []
            for row in transition_rows:
                identity = identities.get(row["path"])
                if identity == row["predecessor_worktree"]:
                    states.append("P")
                elif identity == row["candidate_worktree"]:
                    states.append("C")
                else:
                    raise ValueError(
                        "controlled snapshot transition worktree differs: "
                        f"{row['path']}"
                    )
            phase = states.count("C")
            if states != ["C"] * phase + ["P"] * (len(states) - phase):
                raise ValueError("controlled snapshot transition order differs")
            return phase

        def stable_transition_observation(
            snapshot_root: Path,
        ) -> tuple[dict[str, Any], dict[str, tuple[int, ...]]]:
            identities: dict[str, Any] = {}
            signatures: dict[str, tuple[int, ...]] = {}
            for row in transition_rows:
                relative = row["path"]
                parent_descriptor, name = utility._open_repo_parent_directory(
                    snapshot_root,
                    relative,
                )
                if parent_descriptor is None:
                    raise ValueError(
                        f"controlled snapshot transition path is missing: {relative}"
                    )
                try:
                    before = os.stat(
                        name,
                        dir_fd=parent_descriptor,
                        follow_symlinks=False,
                    )
                    if (
                        not stat.S_ISREG(before.st_mode)
                        or stat.S_IMODE(before.st_mode) != 0o600
                        or before.st_uid != os.geteuid()
                        or before.st_nlink != 1
                    ):
                        raise ValueError(
                            "controlled snapshot transition authority differs: "
                            f"{relative}"
                        )
                    identity = utility._stable_regular_file_identity(
                        parent_descriptor,
                        name,
                        before,
                    )
                    after = os.stat(
                        name,
                        dir_fd=parent_descriptor,
                        follow_symlinks=False,
                    )
                finally:
                    os.close(parent_descriptor)
                signature = (
                    after.st_dev,
                    after.st_ino,
                    after.st_mode,
                    after.st_uid,
                    after.st_gid,
                    after.st_nlink,
                    after.st_size,
                    after.st_mtime_ns,
                    after.st_ctime_ns,
                )
                if signature != (
                    before.st_dev,
                    before.st_ino,
                    before.st_mode,
                    before.st_uid,
                    before.st_gid,
                    before.st_nlink,
                    before.st_size,
                    before.st_mtime_ns,
                    before.st_ctime_ns,
                ):
                    raise ValueError(
                        "controlled snapshot transition identity changed: "
                        f"{relative}"
                    )
                identities[relative] = identity
                signatures[relative] = signature
            return identities, signatures

        initial_identities, initial_signatures = stable_transition_observation(root)
        initial_phase = transition_phase(initial_identities)

        def working_snapshot_hashes_with_overrides(
            snapshot_root: Path,
            paths: list[str],
        ) -> tuple[str, str]:
            normalized = sorted(paths)
            transition_paths = {row["path"] for row in transition_rows}
            if not transition_paths.issubset(normalized):
                raise ValueError(
                    "controlled snapshot transition path is unmanaged"
                )
            identities, signatures = stable_transition_observation(snapshot_root)
            if (
                transition_phase(identities) != initial_phase
                or signatures != initial_signatures
            ):
                raise ValueError("controlled snapshot transition phase changed")
            path_digest = hashlib.sha256(
                ("\n".join(normalized) + "\n").encode("utf-8")
            ).hexdigest()
            content_digest = hashlib.sha256()
            for relative in normalized:
                transition = next(
                    (row for row in transition_rows if row["path"] == relative),
                    None,
                )
                if transition is None:
                    path = utility.resolve_safe_repo_file(snapshot_root, relative)
                    if path is None:
                        raise ValueError(
                            "unsafe or missing working snapshot path: "
                            f"{relative}"
                        )
                    digest = utility.sha256_file(path)
                else:
                    digest = transition["predecessor_worktree"].get("sha256")
                    if not isinstance(digest, str) or re.fullmatch(
                        r"[0-9a-f]{64}", digest
                    ) is None:
                        raise ValueError(
                            "controlled snapshot predecessor SHA-256 is invalid"
                        )
                content_digest.update(relative.encode("utf-8"))
                content_digest.update(b"\0")
                content_digest.update(digest.encode("ascii"))
                content_digest.update(b"\n")
            return path_digest, content_digest.hexdigest()

        utility.working_snapshot_hashes = working_snapshot_hashes_with_overrides
    payload = utility.capture_gate_repository_state(
        root,
        checkpoint_path,
        gate_event_id,
    )
    if transition_rows:
        dirty = payload.get("dirty_snapshot")
        dirty_paths = dirty.get("paths") if isinstance(dirty, dict) else None
        if not isinstance(dirty_paths, list):
            raise ValueError("repository dirty snapshot is missing")
        captured_by_path = {
            row.get("path"): row.get("worktree")
            for row in dirty_paths
            if isinstance(row, dict) and isinstance(row.get("path"), str)
        }
        if transition_phase(captured_by_path) != initial_phase:
            raise ValueError("captured snapshot transition phase changed")
        identities, signatures = stable_transition_observation(root)
        if (
            transition_phase(identities) != initial_phase
            or signatures != initial_signatures
        ):
            raise ValueError("controlled snapshot transition phase changed")
    if ephemeral_exact_exclusion is not None:
        exclusions = payload.get("transaction_exclusions")
        if not isinstance(exclusions, dict):
            raise ValueError("repository transaction exclusions are missing")
        exclusions["allowed_rule_count"] = 3
        exclusions["ephemeral_exact_path"] = ephemeral_exact_exclusion
    return payload
current_head = _v23_utility.current_head
current_branch = _v23_utility.current_branch
is_commit_ancestor = _v23_utility.is_commit_ancestor

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
    "GOAL_START_CONTROL_REANCHORED",
    "GOAL_STARTED",
    "WORK_SESSION_RESUMED",
    "GOAL_COMPLETED",
    "GOAL_FOCUS_CHANGED",
    "GOAL_SUPERSEDED",
    "BLOCKER_RECORDED",
    "BLOCKER_RESOLVED",
    "PACKAGE_COMPLETED",
}
GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE = 72
GENERIC_DEPENDENCY_CLOSURE_DIRECT_FIELDS = (
    "canonical_binding_snapshot_after",
    "changed_binding_roles",
    "changed_subject_ids_by_role",
    "impact_closure_goal_ids",
    "impact_disposition_by_goal",
    "reopened_completion_event_sha256_by_goal",
    "status_changes",
)
GENERIC_AFTER_PROJECTION_FIELDS = {
    "runtime_after",
    "canonical_binding_snapshot_after",
    "completion_evidence_by_goal_after",
    "archived_completion_evidence_by_goal_after",
    "dynamic_goal_inventory_after",
    "materialized_child_goal_ids_by_parent_after",
    "blockers_after",
    "blocker_resolution_ids_after",
}
GENERIC_RUNTIME_AFTER_FIELDS = {
    "focus_goal_id",
    "focus_goal_path",
    "focus_source",
    "focus_work_item_id",
    "ready_frontier_goal_ids",
    "blocked_goal_ids",
    "pending_questions",
    "open_question_count",
    "activation_status",
    "package_status",
    "artifact_work_queue_sha256",
    "completion_boundary_sha256",
}


def _is_dependency_closure_event(event_type: object, event: Mapping[str, Any]) -> bool:
    return (
        event_type == "CANONICAL_BINDINGS_UPDATED"
        and isinstance(event.get("sequence"), int)
        and event["sequence"] >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE
        and "produced_by_goal_id" in event
        and event.get("produced_by_goal_id") is None
        and event.get("produced_binding_roles") == []
        and event.get("producer_completion_receipt_binding") is None
        and event.get("producer_output_subject_ids_by_role") == {}
    )
GOAL_START_CONTROL_REANCHOR_EVENT_FIELDS = {
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
FP022_GOAL_START_CONTROL_REANCHOR_EVENT_FIELDS = {
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
    "source_checkpoint_binding",
    "source_ready_event_binding",
    "contract_supersession",
    "start_gate_runner_binding",
    "transition_control_review_binding",
    "repository_context_reanchor",
    "claim_boundary",
    "unchanged_control_projection",
    "canonical_binding_snapshot_after",
    "previous_event_sha256",
    "event_sha256",
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
FP022_GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
FP022_GOAL_SHA256 = (
    "939075c1b4bcbf9b8280c37cb7a449fd28763f06cda14faf0ca88f691734576b"
)
FP022_START_GATE_CONTRACT_PATH = (
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-04-FP-022-R001/"
    "initial-start-gate-contract-r002.json"
)
FP022_READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-FP022-20260813-001"
)
FP022_READY_EVENT_SHA256 = (
    "37207b4393dd5820de6b71c7e167885f8592875f8b54d9d75d650aef230a87a2"
)
FP022_CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "FP022-20260814-001"
)
FP022_STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
)
FP022_COMPLETION_UPDATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-FP022-20260814-001"
)
FP022_COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP022-20260814-001"
)
FP022_COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{FP022_GOAL_ID}"
FP022_COMPLETION_DOCUMENT_ID = (
    "WS-FP022-NAVIGATION-WORK-ITEM-COMPLETION-20260814-001"
)
FP022_COMPLETION_PATH = (
    "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/"
    "completion-receipt.json"
)
FP022_R028_GAP_PATH = (
    "docs/control/audits/walksafe-implementation-gap-analysis-20260814-r028.json"
)
FP022_R028_BACKLOG_PATH = (
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260814-r028.json"
)
R008_R028_BACKLOG_BINDING = {
    "role": "IMPLEMENTATION_BACKLOG",
    "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
    "path": FP022_R028_BACKLOG_PATH,
    "file_sha256": (
        "acf975cffcdec26906099236567f825a616226bfb030ef70d20bb7a21bcfbe55"
    ),
}
R008_R029_CANONICAL_GAP_BINDING = {
    "role": "IMPLEMENTATION_GAP",
    "document_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029",
    "path": (
        "docs/control/audits/"
        "walksafe-implementation-gap-analysis-20260815-r029.json"
    ),
    "file_sha256": (
        "bf0ae2003d53ab310f2321ea3c3026fc9f255909837f6b874a9a4b3738ad3922"
    ),
}
R008_R029_CANONICAL_BACKLOG_BINDING = {
    "role": "IMPLEMENTATION_BACKLOG",
    "document_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
    "path": (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260815-r029.json"
    ),
    "file_sha256": (
        "8128560569c340ce3b60c24972ccaa6bb5a52e5d13035bd709a3f12a2392aeba"
    ),
}
R008_SEQ72_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-"
    "FP046-R002-20260815-001"
)
R008_R028_LEGACY_BACKLOG_SOURCE_PREDECESSOR = {
    "path": (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260813-r027.json"
    ),
    "file_sha256": (
        "64e91046639ba44600d3584b5258d15f26b9c9161b03f25a4a662903d29e2f45"
    ),
    "byte_length": 65457,
    "markdown_path": (
        "docs/control/audits/"
        "walksafe-implementation-remediation-backlog-20260813-r027.md"
    ),
    "markdown_file_sha256": (
        "4285b7f1095ec340fc228855331039cd9e7064891f114f9e93a3f81846483a14"
    ),
    "markdown_byte_length": 420,
    "preserved_unchanged": True,
}
R008_R029_NORMALIZED_BACKLOG_SOURCE_PREDECESSOR = {
    "path": FP022_R028_BACKLOG_PATH,
    "file_sha256": R008_R028_BACKLOG_BINDING["file_sha256"],
    "preserved_unchanged": True,
}
FP022_PARENT_GOAL_ID = "WS-GOAL-EPIC-04"
FP022_PARENT_GOAL_PATH = (
    "docs/control/goals/walksafe-completion-graph-v2-2/workstreams/"
    "epic-04-navigation-arrival-deviation.md"
)
FP022_PARENT_GOAL_SHA256 = (
    "da4aa5a7ab2abe8c3a746c8df4edd69db77ea1dbc2b3e1bdc00399291b99ac6d"
)
FP022_START_GATE_CHECK_IDS = [
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_NAVIGATION_INTERNAL",
    "ANDROID_USER_INTERNAL",
    "ROOT_FP022_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
]
FP022_START_GATE_RUNTIME_PATHS = [
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/app/gradle.lockfile",
]
NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID = (
    "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001"
)
NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256 = (
    "234a224883779208ba7878a9865076083bb9cfd205dfdb7a760b043f7af6b16d"
)
NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-READY-"
    "NPC-SINGLE-ADMIN-RECOVERY-20260810-001"
)
NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256 = (
    "08e25cd9808e2301a4af7a3b463d5a1cda795caf41eed57a35de29676f2210ef"
)
NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "NPC-20260812-001"
)
NPC_SINGLE_ADMIN_RECOVERY_CONTROL_CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
    "NPC-CORRECTION-20260812-001"
)
NPC_SINGLE_ADMIN_RECOVERY_R001_CONTRACT_PATH = (
    "docs/control/execution/goal-contracts/"
    f"{NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID}/"
    "initial-start-gate-contract-r001.json"
)
NPC_SINGLE_ADMIN_RECOVERY_R002_CONTRACT_PATH = (
    "docs/control/execution/goal-contracts/"
    f"{NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID}/"
    "initial-start-gate-contract-r002.json"
)
NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING = {
    "document_id": (
        "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-"
        "20260810-001"
    ),
    "contract_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R001",
    "contract_version": "2026-08-10.1",
    "path": NPC_SINGLE_ADMIN_RECOVERY_R001_CONTRACT_PATH,
    "file_sha256": (
        "0e9b80005e3ad206af6d43d724dfc70688e6d7ef8907ad6ee2c72da2e60679d1"
    ),
    "canonical_sha256": (
        "b6a8ada662716ee963f1248ffdf5fdd730c545640a35a7fc11ed134016321186"
    ),
}
NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING = {
    "schema_version": "1.1",
    "document_id": (
        "WS-NPC-SINGLE-ADMIN-RECOVERY-INITIAL-START-GATE-CONTRACT-"
        "20260812-002"
    ),
    "path": NPC_SINGLE_ADMIN_RECOVERY_R002_CONTRACT_PATH,
    "file_sha256": (
        "37b843953a5c8089ae2b23224804fbef0c21150c2f2bbf876a57cb4cd3083227"
    ),
    "contract_id": "WS-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-START-GATE-R002",
    "contract_version": "2026-08-12.1",
    "canonical_contract_sha256": (
        "1ca0369ac5be15375514d800b1c5a66f9a3ff3af4c487df6e379f57a54ca67de"
    ),
}
NPC_SINGLE_ADMIN_RECOVERY_START_GATE_CHECK_IDS = [
    "CONTINUATION",
    "V24_ARTIFACT_WORK_QUEUE",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "BACKEND_TEST_DATABASE_PREFLIGHT",
    "BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES",
    "ANDROID_ADMIN_INTERNAL",
    "ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
]
NPC_SINGLE_ADMIN_RECOVERY_START_GATE_RUNTIME_PATHS = [
    "apps/android/gradle/wrapper/gradle-wrapper.properties",
    "apps/android/gradle/wrapper/gradle-wrapper.jar",
    "apps/android/gradle/verification-metadata.xml",
    "apps/android/adminapp/gradle.lockfile",
]
NPC_SINGLE_ADMIN_RECOVERY_AUTHORIZATION_BINDING = {
    "document_id": (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-"
        "AUTHORIZATION-20260812-001"
    ),
    "path": (
        "docs/control/execution/goal-start-control-reanchors/"
        f"{NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID}/"
        "authorization.md"
    ),
    "file_sha256": (
        "1fe494dc36160fae2c6cd4ed344bd1515a23faad3f4f3c2004baf9375ec3e819"
    ),
    "byte_count": 12634,
    "recorded_at": "2026-08-12T22:30:24+09:00",
}
NPC_SINGLE_ADMIN_RECOVERY_REVIEW_BINDING = {
    "document_id": (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-NPC-"
        "INDEPENDENT-REVIEW-20260812-001"
    ),
    "path": (
        "docs/control/execution/goal-start-control-reanchors/"
        f"{NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID}/"
        "independent-review.md"
    ),
    "file_sha256": (
        "3f7b4be0d4687001d2caf9e7c8a2524f928e45c960e60fd6dad0119e68557047"
    ),
    "byte_count": 14182,
    "reviewed_at": "2026-08-12T22:32:19+09:00",
}
NPC_SINGLE_ADMIN_RECOVERY_CORRECTION_AUTHORIZATION_BINDING = {
    "document_id": (
        "WS-GOAL-GRAPH-V2-4-NPC-START-CONTROL-CORRECTION-"
        "AUTHORIZATION-20260812-001"
    ),
    "path": (
        "docs/control/execution/goal-start-control-reanchors/"
        f"{NPC_SINGLE_ADMIN_RECOVERY_CONTROL_CORRECTION_EVENT_ID}/"
        "authorization.md"
    ),
    "file_sha256": (
        "d12ce6bc2b741cb583ea3ddefae2ea3b09852d215cf49f488c4e4550fc0deb8f"
    ),
    "byte_count": 5273,
    "recorded_at": "2026-08-12T23:15:00+09:00",
}
NPC_SINGLE_ADMIN_RECOVERY_CORRECTION_REVIEW_BINDING = {
    "document_id": (
        "WS-GOAL-GRAPH-V2-4-NPC-START-CONTROL-CORRECTION-"
        "REVIEW-20260812-001"
    ),
    "path": (
        "docs/control/execution/goal-start-control-reanchors/"
        f"{NPC_SINGLE_ADMIN_RECOVERY_CONTROL_CORRECTION_EVENT_ID}/"
        "independent-review.md"
    ),
    "file_sha256": (
        "8d2ce430e4b002ca8dc38969858a4d3adf4789268157af969ce7f737e91ccaeb"
    ),
    "byte_count": 3304,
    "reviewed_at": "2026-08-12T23:18:01+09:00",
}
NPC_SINGLE_ADMIN_RECOVERY_SOURCE_REPOSITORY_CONTEXT = {
    "checkpoint_path": "docs/control/walksafe-project-continuation-checkpoint.json",
    "checkpoint_file_sha256": (
        "de3ffafa2d8ff151beedc28e7b5f45296382dcdf93e41280f43136532e54358e"
    ),
    "checkpoint_byte_count": 1796959,
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
NPC_SINGLE_ADMIN_RECOVERY_CORRECTION_SOURCE_REPOSITORY_CONTEXT = {
    "checkpoint_path": "docs/control/walksafe-project-continuation-checkpoint.json",
    "checkpoint_file_sha256": (
        "55b2a209679ddb9573abfeb97b0b24112151884e756133a4faef34879257d2d4"
    ),
    "checkpoint_byte_count": 1770409,
    "branch": "current",
    "base_commit": "f0093863e82bfc80d9f11915cef33a51d44b8730",
    "current_head": "ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c",
    "managed_changed_path_count": 639,
    "path_set_sha256": (
        "fcf3627f3beb8675930c990fa9ac336f48012e95b19bf9c78dbf6f26bf4add10"
    ),
    "content_set_sha256": (
        "279899fa496301f3c5339fa2f61e43983e25d224e964348ef67c5b76b995c5a3"
    ),
}
NPC_SINGLE_ADMIN_RECOVERY_FAILED_GATE_LOG_BINDING = {
    "path": (
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
        "20260812-001/07-ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION.log"
    ),
    "file_sha256": (
        "d6a2657c3848d292eea440adeb3afc68f210ae3bcc0a60c0a0eb1d1b888bb05b"
    ),
    "byte_count": 2324,
}
NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT = (
    "f0093863e82bfc80d9f11915cef33a51d44b8730"
)
NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT = (
    "ca0898d56eaa45b947b9f513a2bcdbfcb5bc5a0c"
)
NPC_SINGLE_ADMIN_RECOVERY_UNCHANGED_CONTROL_SHA256 = {
    "artifact": "86f6ea0814924165caf6a79ed3b47ef0989806c463ae4696c3dfc42cecd21b2a",
    "canonical": "f3de1183e57bbeff58d758b5dfc076f7a80696e7001fce359ee14d0f6700b489",
    "completion": "6d40a80353384f59c6e9f10653a1a92e7c51e7b31135d97bb8779fd54a18d1d6",
    "current_work": "c07e8b352186720ed575c562c31655ff3c90586c058093c5276dd1f5cf6663d9",
    "runtime": "e0e959210e6523e7082e9b3e5bd7ed2fa21a8e07d396623a2995285d5e738bb8",
    "status": "e3d38b7859d6ebbf4fab2cd708e77510eb17b83d57035a64f7bcb5af05fb0283",
    "verification": "406f4ec4b66dd3672e1fc2246c5ec2ce4c1ea6fa388549e6dabb52e49f2ac11c",
}
NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_CLAIM_BOUNDARY = {
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
START_GATE_RUNTIME_BINDING_AMENDMENTS = {
    event_id: {
        "apps/android/gradle/verification-metadata.xml": {
            "sealed_sha256": (
                "0f2fc21ad52bd81b877f4a2cecdf587841f4dcac2c87e0e3139a0f94374c0084"
            ),
            "current_sha256": (
                "eaa662a434257a71c595ab510889657579e5e5a107041813338a257b56674d17"
            ),
        }
    }
    for event_id in (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002",
        "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005",
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005",
    )
}
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


def _validate_regular_file_binding(
    root: Path,
    value: Any,
    *,
    expected: dict[str, Any],
    label: str,
) -> list[str]:
    errors: list[str] = []
    if value != expected:
        errors.append(f"{label} binding differs")
        return errors
    relative = expected["path"]
    path = resolve_repo_file(root, relative)
    if path is None or _contains_symlink(root, relative):
        return [f"{label} path is missing or unsafe"]
    try:
        metadata = path.stat()
    except OSError as exc:
        return [f"{label} metadata cannot be read: {exc}"]
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_nlink != 1
        or metadata.st_size != expected["byte_count"]
        or sha256_file(path) != expected["file_sha256"]
    ):
        errors.append(f"{label} physical file differs")
    return errors


def _load_npc_single_admin_recovery_r002_contract(
    root: Path,
) -> tuple[list[str], list[dict[str, str]], dict[str, Any]]:
    errors: list[str] = []
    binding = NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING
    relative = binding["path"]
    path = resolve_repo_file(root, relative)
    if path is None or _contains_symlink(root, relative):
        return ["NPC R002 start-gate contract path is missing or unsafe"], [], {}
    if sha256_file(path) != binding["file_sha256"]:
        errors.append("NPC R002 start-gate contract file SHA-256 differs")
    try:
        contract = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"NPC R002 start-gate contract cannot be loaded: {exc}"], [], {}
    if canonical_json_sha256(contract) != binding["canonical_contract_sha256"]:
        errors.append("NPC R002 start-gate canonical SHA-256 differs")
    expected_fields = {
        "schema_version",
        "document_id",
        "contract_id",
        "contract_version",
        "target_goal_id",
        "target_goal_content_sha256",
        "gate_purpose",
        "successor_reason_code",
        "supersedes",
        "ordered_checks",
        "claim_boundary",
    }
    if set(contract) != expected_fields:
        errors.append("NPC R002 start-gate contract field set differs")
    for label, actual, expected in (
        ("schema", contract.get("schema_version"), "1.1"),
        ("document ID", contract.get("document_id"), binding["document_id"]),
        ("contract ID", contract.get("contract_id"), binding["contract_id"]),
        ("version", contract.get("contract_version"), binding["contract_version"]),
        ("target Goal", contract.get("target_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        (
            "target Goal SHA-256",
            contract.get("target_goal_content_sha256"),
            NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256,
        ),
        ("purpose", contract.get("gate_purpose"), "INITIAL_START"),
        (
            "reason",
            contract.get("successor_reason_code"),
            "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
        ),
    ):
        _require_equal(errors, f"NPC R002 start-gate {label}", actual, expected)
    expected_supersedes = {
        **NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING,
        "source_ready_event_sequence": 57,
        "source_ready_event_id": NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID,
        "source_ready_event_sha256": NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256,
    }
    _require_equal(
        errors,
        "NPC R002 start-gate predecessor binding",
        contract.get("supersedes"),
        expected_supersedes,
    )
    raw_checks = contract.get("ordered_checks")
    checks: list[dict[str, str]] = []
    if not isinstance(raw_checks, list):
        errors.append("NPC R002 start-gate ordered checks are missing")
    else:
        for index, item in enumerate(raw_checks, start=1):
            if (
                not isinstance(item, dict)
                or set(item) != {"check_id", "command"}
                or not isinstance(item.get("check_id"), str)
                or not isinstance(item.get("command"), str)
                or not item["command"]
            ):
                errors.append(f"NPC R002 start-gate ordered check {index} differs")
                continue
            checks.append({"check_id": item["check_id"], "command": item["command"]})
    _require_equal(
        errors,
        "NPC R002 start-gate ordered check IDs",
        [item["check_id"] for item in checks],
        NPC_SINGLE_ADMIN_RECOVERY_START_GATE_CHECK_IDS,
    )
    forbidden = (
        "apps/web",
        "android-gateway",
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
        errors.append("NPC R002 start-gate contract contains forbidden command scope")
    return errors, checks, contract


def _validate_npc_single_admin_recovery_control_reanchor_seq58(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
    history: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    if set(event) != GOAL_START_CONTROL_REANCHOR_EVENT_FIELDS:
        errors.append("NPC start-control reanchor event field set differs")
    if event.get("sequence") != 58 or len(history) < 58:
        return errors + ["NPC start-control reanchor sequence differs"]
    ready = history[56]
    if not isinstance(ready, dict):
        return errors + ["NPC start-control reanchor READY source is missing"]
    expected_source_ready = {
        "sequence": 57,
        "event_id": NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID,
        "event_sha256": NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256,
        "goal_id": NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID,
        "status": "READY",
    }
    for label, actual, expected in (
        ("event ID", event.get("event_id"), NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID),
        ("event type", event.get("event_type"), "GOAL_START_CONTROL_REANCHORED"),
        ("occurred on", event.get("occurred_on"), "2026-08-12"),
        ("occurred at", event.get("occurred_at"), "2026-08-12T22:32:20+09:00"),
        ("previous focus", event.get("previous_focus_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        ("previous focus content", event.get("previous_focus_content_sha256"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256),
        ("focus", event.get("focus_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        ("focus content", event.get("focus_goal_content_sha256"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256),
        ("subject", event.get("subject_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        ("from status", event.get("from_status"), "READY"),
        ("to status", event.get("to_status"), "READY"),
        ("status changes", event.get("status_changes"), {}),
        ("source checkpoint version", event.get("source_checkpoint_version"), "1.25.0"),
        ("source READY binding", event.get("source_ready_event_binding"), expected_source_ready),
        ("previous event", event.get("previous_event_sha256"), NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256),
        ("runtime", event.get("runtime_after"), ready.get("runtime_after")),
        ("blockers", event.get("blockers_after"), ready.get("blockers_after")),
        (
            "blocker resolutions",
            event.get("blocker_resolution_ids_after"),
            ready.get("blocker_resolution_ids_after"),
        ),
        (
            "evidence refs",
            event.get("evidence_refs"),
            [
                "GOAL_START_CONTROL_REANCHOR_AUTHORIZATION",
                "GOAL_START_CONTROL_REANCHOR_INDEPENDENT_REVIEW",
                "INITIAL_START_GATE_CONTRACT_SUCCESSOR",
            ],
        ),
        (
            "claim boundary",
            event.get("claim_boundary"),
            NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_CLAIM_BOUNDARY,
        ),
    ):
        _require_equal(errors, f"NPC start-control reanchor {label}", actual, expected)
    if (
        ready.get("sequence") != 57
        or ready.get("event_id") != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
        or ready.get("event_sha256") != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256
        or ready.get("event_sha256") != event_sha256(ready)
        or ready.get("implementation_start_gate_contract_binding")
        != NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING
    ):
        errors.append("NPC start-control reanchor seq57 source differs")
    expected_supersession = {
        "previous_contract_binding": NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING,
        "replacement_contract_binding": NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING,
        "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
    }
    _require_equal(
        errors,
        "NPC start-control reanchor contract supersession",
        event.get("contract_supersession"),
        expected_supersession,
    )
    contract_errors, _, _ = _load_npc_single_admin_recovery_r002_contract(root)
    errors.extend(contract_errors)
    errors.extend(
        _validate_regular_file_binding(
            root,
            event.get("authorization_binding"),
            expected=NPC_SINGLE_ADMIN_RECOVERY_AUTHORIZATION_BINDING,
            label="NPC start-control reanchor authorization",
        )
    )
    errors.extend(
        _validate_regular_file_binding(
            root,
            event.get("independent_review_binding"),
            expected=NPC_SINGLE_ADMIN_RECOVERY_REVIEW_BINDING,
            label="NPC start-control reanchor independent review",
        )
    )
    expected_unchanged = {
        name: {"before_sha256": digest, "after_sha256": digest}
        for name, digest in sorted(
            NPC_SINGLE_ADMIN_RECOVERY_UNCHANGED_CONTROL_SHA256.items()
        )
    }
    _require_equal(
        errors,
        "NPC start-control reanchor unchanged projection",
        event.get("unchanged_control_projection"),
        expected_unchanged,
    )
    repository_context = event.get("repository_context_reanchor")
    before = repository_context.get("before") if isinstance(repository_context, dict) else None
    after = repository_context.get("after") if isinstance(repository_context, dict) else None
    _require_equal(
        errors,
        "NPC start-control reanchor repository before",
        before,
        NPC_SINGLE_ADMIN_RECOVERY_SOURCE_REPOSITORY_CONTEXT,
    )
    expected_after_fields = {
        "branch",
        "base_commit",
        "current_head",
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
    }
    if not isinstance(after, dict) or set(after) != expected_after_fields:
        errors.append("NPC start-control reanchor repository after field set differs")
        after = {}
    for label, actual, expected in (
        ("branch", after.get("branch"), "current"),
        ("base commit", after.get("base_commit"), NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT),
        ("current HEAD", after.get("current_head"), NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT),
    ):
        _require_equal(errors, f"NPC start-control reanchor repository {label}", actual, expected)
    required_after_paths = set(EXPECTED_CONTROLLED_PATHS) | set(
        expected_goal_paths(checkpoint.get("goal_execution", {}))
    )
    if (
        not isinstance(after.get("managed_changed_path_count"), int)
        or after.get("managed_changed_path_count", 0) < len(required_after_paths)
        or not isinstance(after.get("path_set_sha256"), str)
        or SHA256_RE.fullmatch(after.get("path_set_sha256", "")) is None
        or not isinstance(after.get("content_set_sha256"), str)
        or SHA256_RE.fullmatch(after.get("content_set_sha256", "")) is None
    ):
        errors.append("NPC start-control reanchor repository snapshot summary differs")
    ancestor = is_commit_ancestor(
        root,
        NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT,
        NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT,
    )
    if ancestor is not True:
        errors.append("NPC start-control reanchor repository ancestry differs")
    if event.get("event_sha256") != event_sha256(event):
        errors.append("NPC start-control reanchor event seal differs")

    # While seq58 is the live tail, the event, checkpoint, and live repository
    # must describe one exact repository state.  Later execution events retain
    # this event as immutable historical start-control evidence.
    if len(history) == 58:
        repository = checkpoint.get("repository")
        snapshot = checkpoint.get("working_tree_snapshot")
        handoff = checkpoint.get("session_handoff")
        source_snapshot = (
            handoff.get("source_commit_or_snapshot")
            if isinstance(handoff, dict)
            else None
        )
        paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
        if not isinstance(paths, list):
            errors.append("NPC start-control reanchor managed paths are missing")
        else:
            try:
                path_digest, content_digest = working_snapshot_hashes(root, paths)
            except (OSError, RuntimeError, ValueError) as exc:
                errors.append(f"NPC start-control reanchor snapshot cannot be reproduced: {exc}")
            else:
                expected_live_after = {
                    "branch": "current",
                    "base_commit": NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT,
                    "current_head": NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT,
                    "managed_changed_path_count": len(paths),
                    "path_set_sha256": path_digest,
                    "content_set_sha256": content_digest,
                }
                _require_equal(
                    errors,
                    "NPC start-control reanchor live repository context",
                    after,
                    expected_live_after,
                )
                if (
                    not isinstance(repository, dict)
                    or repository.get("branch") != "current"
                    or repository.get("snapshot_base_head")
                    != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT
                    or snapshot.get("base_head")
                    != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT
                    or snapshot.get("managed_changed_path_count") != len(paths)
                    or snapshot.get("path_set_sha256") != path_digest
                    or snapshot.get("content_set_sha256") != content_digest
                    or not isinstance(source_snapshot, dict)
                    or handoff.get("branch") != "current"
                    or handoff.get("changed_files") != paths
                    or source_snapshot.get("base_commit")
                    != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT
                    or source_snapshot.get("current_head")
                    != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT
                    or source_snapshot.get("file_count") != len(paths)
                    or source_snapshot.get("path_set_sha256") != path_digest
                    or source_snapshot.get("content_set_sha256") != content_digest
                ):
                    errors.append("NPC start-control reanchor checkpoint repository projection differs")
        if current_branch(root) != "current" or current_head(root) != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT:
            errors.append("NPC start-control reanchor live Git identity differs")
    return errors


def _validate_npc_single_admin_recovery_control_correction_seq59(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
    history: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    label = "NPC start-control correction"
    if set(event) != GOAL_START_CONTROL_REANCHOR_EVENT_FIELDS:
        errors.append(f"{label} event field set differs")
    if event.get("sequence") != 59 or len(history) < 59:
        return errors + [f"{label} sequence differs"]
    ready = history[56]
    reanchor = history[57]
    if not isinstance(ready, dict) or not isinstance(reanchor, dict):
        return errors + [f"{label} source lineage is missing"]

    expected_source_ready = {
        "sequence": 57,
        "event_id": NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID,
        "event_sha256": NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256,
        "goal_id": NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID,
        "status": "READY",
    }
    for item_label, actual, expected in (
        ("event ID", event.get("event_id"), NPC_SINGLE_ADMIN_RECOVERY_CONTROL_CORRECTION_EVENT_ID),
        ("event type", event.get("event_type"), "GOAL_START_CONTROL_REANCHORED"),
        ("occurred on", event.get("occurred_on"), "2026-08-12"),
        ("occurred at", event.get("occurred_at"), "2026-08-12T23:18:02+09:00"),
        ("previous focus", event.get("previous_focus_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        ("previous focus content", event.get("previous_focus_content_sha256"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256),
        ("focus", event.get("focus_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        ("focus content", event.get("focus_goal_content_sha256"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256),
        ("subject", event.get("subject_goal_id"), NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID),
        ("from status", event.get("from_status"), "READY"),
        ("to status", event.get("to_status"), "READY"),
        ("status changes", event.get("status_changes"), {}),
        ("source checkpoint version", event.get("source_checkpoint_version"), "1.25.0"),
        ("source READY binding", event.get("source_ready_event_binding"), expected_source_ready),
        ("previous event", event.get("previous_event_sha256"), "929ff8b16ec13ad9bd697148f6cee600e4491e627339fdf4d2aa325b6a64c38b"),
        ("runtime", event.get("runtime_after"), reanchor.get("runtime_after")),
        ("blockers", event.get("blockers_after"), reanchor.get("blockers_after")),
        (
            "blocker resolutions",
            event.get("blocker_resolution_ids_after"),
            reanchor.get("blocker_resolution_ids_after"),
        ),
        (
            "evidence refs",
            event.get("evidence_refs"),
            [
                "FAILED_START_GATE_001_CORRECTION",
                "GOAL_START_CONTROL_CORRECTION_AUTHORIZATION",
                "GOAL_START_CONTROL_CORRECTION_INDEPENDENT_REVIEW",
                "INITIAL_START_GATE_CONTRACT_SUCCESSOR",
            ],
        ),
        ("claim boundary", event.get("claim_boundary"), NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_CLAIM_BOUNDARY),
    ):
        _require_equal(errors, f"{label} {item_label}", actual, expected)

    if (
        ready.get("sequence") != 57
        or ready.get("event_id") != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
        or ready.get("event_sha256") != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256
        or ready.get("event_sha256") != event_sha256(ready)
        or ready.get("implementation_start_gate_contract_binding")
        != NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING
    ):
        errors.append(f"{label} seq57 source differs")
    if (
        reanchor.get("sequence") != 58
        or reanchor.get("event_id") != NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID
        or reanchor.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or reanchor.get("subject_goal_id") != NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
        or reanchor.get("event_sha256")
        != "929ff8b16ec13ad9bd697148f6cee600e4491e627339fdf4d2aa325b6a64c38b"
        or reanchor.get("event_sha256") != event_sha256(reanchor)
    ):
        errors.append(f"{label} seq58 source differs")

    expected_supersession = {
        "previous_contract_binding": NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING,
        "replacement_contract_binding": NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING,
        "reason_code": "CURRENT_TEST_LAYER_REGISTRY_RUNNER_REQUIRED",
    }
    _require_equal(
        errors,
        f"{label} contract supersession",
        event.get("contract_supersession"),
        expected_supersession,
    )
    contract_errors, _, _ = _load_npc_single_admin_recovery_r002_contract(root)
    errors.extend(contract_errors)
    errors.extend(
        _validate_regular_file_binding(
            root,
            event.get("authorization_binding"),
            expected=NPC_SINGLE_ADMIN_RECOVERY_CORRECTION_AUTHORIZATION_BINDING,
            label=f"{label} authorization",
        )
    )
    errors.extend(
        _validate_regular_file_binding(
            root,
            event.get("independent_review_binding"),
            expected=NPC_SINGLE_ADMIN_RECOVERY_CORRECTION_REVIEW_BINDING,
            label=f"{label} independent review",
        )
    )
    expected_unchanged = {
        name: {"before_sha256": digest, "after_sha256": digest}
        for name, digest in sorted(
            NPC_SINGLE_ADMIN_RECOVERY_UNCHANGED_CONTROL_SHA256.items()
        )
    }
    _require_equal(
        errors,
        f"{label} unchanged projection",
        event.get("unchanged_control_projection"),
        expected_unchanged,
    )

    repository_context = event.get("repository_context_reanchor")
    before = repository_context.get("before") if isinstance(repository_context, dict) else None
    after = repository_context.get("after") if isinstance(repository_context, dict) else None
    _require_equal(
        errors,
        f"{label} repository before",
        before,
        NPC_SINGLE_ADMIN_RECOVERY_CORRECTION_SOURCE_REPOSITORY_CONTEXT,
    )
    expected_after_fields = {
        "branch",
        "base_commit",
        "current_head",
        "managed_changed_path_count",
        "path_set_sha256",
        "content_set_sha256",
    }
    if not isinstance(after, dict) or set(after) != expected_after_fields:
        errors.append(f"{label} repository after field set differs")
        after = {}
    for item_label, actual, expected in (
        ("branch", after.get("branch"), "current"),
        ("base commit", after.get("base_commit"), NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT),
        ("current HEAD", after.get("current_head"), NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT),
    ):
        _require_equal(errors, f"{label} repository {item_label}", actual, expected)
    required_after_paths = set(EXPECTED_CONTROLLED_PATHS) | set(
        expected_goal_paths(checkpoint.get("goal_execution", {}))
    )
    if (
        not isinstance(after.get("managed_changed_path_count"), int)
        or after.get("managed_changed_path_count", 0) < len(required_after_paths)
        or not isinstance(after.get("path_set_sha256"), str)
        or SHA256_RE.fullmatch(after.get("path_set_sha256", "")) is None
        or not isinstance(after.get("content_set_sha256"), str)
        or SHA256_RE.fullmatch(after.get("content_set_sha256", "")) is None
    ):
        errors.append(f"{label} repository snapshot summary differs")

    failed_log = NPC_SINGLE_ADMIN_RECOVERY_FAILED_GATE_LOG_BINDING
    failed_path = resolve_repo_file(root, failed_log["path"])
    if failed_path is None or _contains_symlink(root, failed_log["path"]):
        errors.append(f"{label} failed-gate log is missing or unsafe")
    else:
        try:
            metadata = failed_path.stat()
        except OSError as exc:
            errors.append(f"{label} failed-gate log metadata cannot be read: {exc}")
        else:
            if (
                not stat.S_ISREG(metadata.st_mode)
                or metadata.st_nlink != 1
                or metadata.st_size != failed_log["byte_count"]
                or sha256_file(failed_path) != failed_log["file_sha256"]
            ):
                errors.append(f"{label} failed-gate log differs")

    failed_directory = failed_path.parent if failed_path is not None else None
    expected_failed_entries = {
        "01-CONTINUATION.log",
        "02-V24_ARTIFACT_WORK_QUEUE.log",
        "03-TEST_LAYER_REGISTRY_VALIDATE.log",
        "04-BACKEND_TEST_DATABASE_PREFLIGHT.log",
        "05-BACKEND_ADMIN_SECURITY_RECOVERY_POSTGRES.log",
        "06-ANDROID_ADMIN_INTERNAL.log",
        "07-ROOT_NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REGRESSION.log",
    }
    if failed_directory is not None:
        try:
            actual_failed_entries = {item.name for item in failed_directory.iterdir()}
        except OSError as exc:
            errors.append(f"{label} failed-gate directory cannot be read: {exc}")
        else:
            if actual_failed_entries != expected_failed_entries:
                errors.append(f"{label} failed-gate inventory differs")

    if event.get("event_sha256") != event_sha256(event):
        errors.append(f"{label} event seal differs")
    if is_commit_ancestor(
        root,
        NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT,
        NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT,
    ) is not True:
        errors.append(f"{label} repository ancestry differs")

    if len(history) == 59:
        repository = checkpoint.get("repository")
        snapshot = checkpoint.get("working_tree_snapshot")
        handoff = checkpoint.get("session_handoff")
        source_snapshot = (
            handoff.get("source_commit_or_snapshot")
            if isinstance(handoff, dict)
            else None
        )
        paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
        if not isinstance(paths, list):
            errors.append(f"{label} managed paths are missing")
        else:
            try:
                path_digest, content_digest = working_snapshot_hashes(root, paths)
            except (OSError, RuntimeError, ValueError) as exc:
                errors.append(f"{label} snapshot cannot be reproduced: {exc}")
            else:
                expected_live_after = {
                    "branch": "current",
                    "base_commit": NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT,
                    "current_head": NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT,
                    "managed_changed_path_count": len(paths),
                    "path_set_sha256": path_digest,
                    "content_set_sha256": content_digest,
                }
                _require_equal(errors, f"{label} live repository context", after, expected_live_after)
                if (
                    not isinstance(repository, dict)
                    or repository.get("branch") != "current"
                    or repository.get("snapshot_base_head") != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT
                    or snapshot.get("base_head") != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT
                    or snapshot.get("managed_changed_path_count") != len(paths)
                    or snapshot.get("path_set_sha256") != path_digest
                    or snapshot.get("content_set_sha256") != content_digest
                    or not isinstance(source_snapshot, dict)
                    or handoff.get("branch") != "current"
                    or handoff.get("changed_files") != paths
                    or source_snapshot.get("base_commit") != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_BASE_COMMIT
                    or source_snapshot.get("current_head") != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT
                    or source_snapshot.get("file_count") != len(paths)
                    or source_snapshot.get("path_set_sha256") != path_digest
                    or source_snapshot.get("content_set_sha256") != content_digest
                ):
                    errors.append(f"{label} checkpoint repository projection differs")
        if current_branch(root) != "current" or current_head(root) != NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_HEAD_COMMIT:
            errors.append(f"{label} live Git identity differs")
    return errors


def _fp022_reanchor_repository_after(
    checkpoint: dict[str, Any],
    history: list[dict[str, Any]],
) -> dict[str, Any] | None:
    started = history[68] if len(history) >= 69 and isinstance(history[68], dict) else None
    fp022_started = bool(
        isinstance(started, dict)
        and started.get("sequence") == 69
        and started.get("event_id")
        == "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
        and started.get("event_type") == "GOAL_STARTED"
        and started.get("subject_goal_id") == FP022_GOAL_ID
        and started.get("previous_event_sha256")
        == history[67].get("event_sha256")
    )
    if not fp022_started:
        snapshot = checkpoint.get("working_tree_snapshot")
        handoff = checkpoint.get("session_handoff")
        mirror = (
            handoff.get("source_commit_or_snapshot")
            if isinstance(handoff, dict)
            else None
        )
        if not isinstance(snapshot, dict) or not isinstance(mirror, dict):
            return None
        return {
            "branch": "current",
            "base_commit": mirror.get("base_commit"),
            "current_head": mirror.get("current_head"),
            "managed_changed_path_count": snapshot.get("managed_changed_path_count"),
            "path_set_sha256": snapshot.get("path_set_sha256"),
            "content_set_sha256": snapshot.get("content_set_sha256"),
        }
    source = started.get("repository_snapshot_before")
    if not isinstance(source, dict):
        return None
    return {
        "branch": source.get("branch"),
        "base_commit": source.get("checkpoint_base_head"),
        "current_head": source.get("head_commit"),
        "managed_changed_path_count": source.get("checkpoint_managed_path_count"),
        "path_set_sha256": source.get("checkpoint_path_set_sha256"),
        "content_set_sha256": source.get("checkpoint_content_set_sha256"),
    }


def _fp022_frozen_transition_review_binding(
    root: Path,
) -> dict[str, dict[str, Any]]:
    from scripts import (
        build_walksafe_fp022_completion_seq70_71_review_20260814
        as completion_review,
    )

    rows = completion_review.prepare_frozen_start_review(root)
    if len(rows) != 3:
        raise RuntimeError("frozen FP022 R031 review inventory differs")
    return {
        "assignment": copy.deepcopy(rows[0]),
        "review_result": copy.deepcopy(rows[1]),
        "independent_review": copy.deepcopy(rows[2]),
    }


def _validate_fp022_control_reanchor_seq68(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
    history: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    label = "FP022 start-control reanchor"
    try:
        from scripts import (
            apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814
            as authority,
        )
    except (ImportError, RuntimeError) as exc:
        return [f"{label} authority cannot be loaded: {exc}"]
    if set(event) != FP022_GOAL_START_CONTROL_REANCHOR_EVENT_FIELDS:
        errors.append(f"{label} event field set differs")
    if event.get("sequence") != 68 or len(history) < 68:
        return errors + [f"{label} sequence differs"]
    ready = history[66]
    if not isinstance(ready, dict):
        return errors + [f"{label} seq67 READY source is missing"]
    try:
        _, successor_binding = authority._load_r002_contract(root)
        review_binding = _fp022_frozen_transition_review_binding(root)
        runner_binding = authority.start_gate_runner_binding(root)
    except (
        AttributeError,
        KeyError,
        OSError,
        RuntimeError,
        TypeError,
        UnicodeError,
        ValueError,
    ) as exc:
        return errors + [f"{label} control authority differs: {exc}"]
    expected_supersession = {
        "previous_contract_binding": authority.r001_contract_binding(),
        "replacement_contract_binding": successor_binding,
        "reason_code": authority.SUCCESSOR_REASON_CODE,
    }
    for item_label, actual, expected in (
        ("event ID", event.get("event_id"), FP022_CONTROL_REANCHOR_EVENT_ID),
        ("event type", event.get("event_type"), "GOAL_START_CONTROL_REANCHORED"),
        ("occurred on", event.get("occurred_on"), "2026-08-14"),
        ("occurred at", event.get("occurred_at"), authority.OCCURRED_AT),
        ("previous focus", event.get("previous_focus_goal_id"), FP022_GOAL_ID),
        ("previous focus content", event.get("previous_focus_content_sha256"), FP022_GOAL_SHA256),
        ("focus", event.get("focus_goal_id"), FP022_GOAL_ID),
        ("focus content", event.get("focus_goal_content_sha256"), FP022_GOAL_SHA256),
        ("subject", event.get("subject_goal_id"), FP022_GOAL_ID),
        ("from status", event.get("from_status"), "READY"),
        ("to status", event.get("to_status"), "READY"),
        ("status changes", event.get("status_changes"), {}),
        ("source checkpoint version", event.get("source_checkpoint_version"), "1.25.0"),
        ("source checkpoint", event.get("source_checkpoint_binding"), authority.source_checkpoint_binding()),
        ("source READY", event.get("source_ready_event_binding"), authority.source_ready_event_binding()),
        ("contract supersession", event.get("contract_supersession"), expected_supersession),
        ("start-gate runner", event.get("start_gate_runner_binding"), runner_binding),
        ("transition review", event.get("transition_control_review_binding"), review_binding),
        ("claim boundary", event.get("claim_boundary"), authority.CLAIM_BOUNDARY),
        ("previous event", event.get("previous_event_sha256"), FP022_READY_EVENT_SHA256),
    ):
        _require_equal(errors, f"{label} {item_label}", actual, expected)
    if (
        ready.get("sequence") != 67
        or ready.get("event_id") != FP022_READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != FP022_GOAL_ID
        or ready.get("event_sha256") != FP022_READY_EVENT_SHA256
        or ready.get("event_sha256") != event_sha256(ready)
    ):
        errors.append(f"{label} seq67 READY source differs")
    for item_label, actual, expected in (
        ("runtime", event.get("runtime_after"), ready.get("runtime_after")),
        ("blockers", event.get("blockers_after"), ready.get("blockers_after")),
        ("blocker resolutions", event.get("blocker_resolution_ids_after"), ready.get("blocker_resolution_ids_after")),
        (
            "canonical snapshot",
            event.get("canonical_binding_snapshot_after"),
            ready.get("canonical_binding_snapshot_after"),
        ),
        (
            "unchanged projection",
            event.get("unchanged_control_projection"),
            {
                name: {"before_sha256": digest, "after_sha256": digest}
                for name, digest in sorted(
                    authority.SOURCE_UNCHANGED_CONTROL_SHA256.items()
                )
            },
        ),
    ):
        _require_equal(errors, f"{label} {item_label}", actual, expected)
    if event.get("event_sha256") != event_sha256(event):
        errors.append(f"{label} event seal differs")
    repository_context = event.get("repository_context_reanchor")
    before = (
        repository_context.get("before")
        if isinstance(repository_context, dict)
        else None
    )
    after = (
        repository_context.get("after")
        if isinstance(repository_context, dict)
        else None
    )
    _require_equal(
        errors,
        f"{label} repository before",
        before,
        authority.expected_source_repository_context(),
    )
    expected_after = _fp022_reanchor_repository_after(checkpoint, history)
    _require_equal(errors, f"{label} repository after", after, expected_after)
    return errors


def _validate_npc_single_admin_recovery_control_reanchor(
    root: Path,
    *,
    event: dict[str, Any],
    checkpoint: dict[str, Any],
    history: list[dict[str, Any]],
) -> list[str]:
    if event.get("subject_goal_id") == FP022_GOAL_ID:
        return _validate_fp022_control_reanchor_seq68(
            root,
            event=event,
            checkpoint=checkpoint,
            history=history,
        )
    event_id = event.get("event_id")
    if event_id == NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID:
        return _validate_npc_single_admin_recovery_control_reanchor_seq58(
            root,
            event=event,
            checkpoint=checkpoint,
            history=history,
        )
    if event_id == NPC_SINGLE_ADMIN_RECOVERY_CONTROL_CORRECTION_EVENT_ID:
        return _validate_npc_single_admin_recovery_control_correction_seq59(
            root,
            event=event,
            checkpoint=checkpoint,
            history=history,
        )
    return ["NPC start-control reanchor event ID is not recognized"]


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
        FP022_START_GATE_CHECK_IDS,
        NPC_SINGLE_ADMIN_RECOVERY_START_GATE_CHECK_IDS,
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
            f"{label} inventory must contain only the {len(check_ids)} "
            "ordered logs and receipt"
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


def _validate_start_gate_runtime_bindings(
    root: Path,
    value: Any,
    *,
    event_id: str,
    expected_paths: list[str],
    label: str,
) -> list[str]:
    errors: list[str] = []
    if not isinstance(value, list) or len(value) != len(expected_paths):
        return [f"{label} start gate runtime bindings differ"]
    for index, relative in enumerate(expected_paths):
        item = value[index]
        if (
            not isinstance(item, dict)
            or set(item) != {"path", "file_sha256"}
            or item.get("path") != relative
        ):
            errors.append(f"{label} start gate runtime bindings differ")
            continue
        path = resolve_repo_file(root, relative)
        if path is None or _contains_symlink(root, relative):
            errors.append(
                f"{label} start gate runtime binding is missing or unsafe: "
                f"{relative}"
            )
            continue
        current_sha256 = sha256_file(path)
        amendment = START_GATE_RUNTIME_BINDING_AMENDMENTS.get(
            event_id,
            {},
        ).get(relative)
        if amendment is None:
            sealed_sha256 = current_sha256
        else:
            sealed_sha256 = amendment["sealed_sha256"]
            _require_equal(
                errors,
                f"{label} start gate current runtime binding {relative}",
                current_sha256,
                amendment["current_sha256"],
            )
        _require_equal(
            errors,
            f"{label} start gate sealed runtime binding {relative}",
            item,
            {"path": relative, "file_sha256": sealed_sha256},
        )
    return errors


def _validate_fp008_runtime_bindings(
    root: Path,
    value: Any,
    *,
    event_id: str,
) -> list[str]:
    return _validate_start_gate_runtime_bindings(
        root,
        value,
        event_id=event_id,
        expected_paths=FP008_START_GATE_RUNTIME_PATHS,
        label="FP008",
    )


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
    *,
    event_id: str,
) -> list[str]:
    return _validate_start_gate_runtime_bindings(
        root,
        value,
        event_id=event_id,
        expected_paths=FP046_START_GATE_RUNTIME_PATHS,
        label="FP046",
    )


def _fp022_start_gate_contract(
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
        and item.get("subject_goal_id") == FP022_GOAL_ID
        and isinstance(item.get("sequence"), int)
        and isinstance(event_sequence, int)
        and item["sequence"] < event_sequence
    ] if isinstance(history, list) else []
    if len(ready_events) != 1:
        return ["FP022 start gate READY event is missing or ambiguous"], [], {}, {}
    ready = ready_events[0]
    reanchor_events = [
        item
        for item in history
        if isinstance(item, dict)
        and item.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and item.get("subject_goal_id") == FP022_GOAL_ID
        and isinstance(item.get("sequence"), int)
        and isinstance(event_sequence, int)
        and item["sequence"] < event_sequence
    ] if isinstance(history, list) else []
    if len(reanchor_events) != 1:
        return ["FP022 start-control reanchor is missing or ambiguous"], [], {}, ready
    reanchor = reanchor_events[0]
    if (
        event.get("event_id") != FP022_STARTED_EVENT_ID
    ):
        errors.append("FP022 start gate event ID differs")
    if (
        ready.get("sequence") != 67
        or ready.get("event_id") != FP022_READY_EVENT_ID
        or ready.get("event_sha256") != event_sha256(ready)
        or ready.get("event_sha256") != FP022_READY_EVENT_SHA256
        or reanchor.get("sequence") != 68
        or reanchor.get("event_id") != FP022_CONTROL_REANCHOR_EVENT_ID
        or reanchor.get("previous_event_sha256") != ready.get("event_sha256")
        or reanchor.get("event_sha256") != event_sha256(reanchor)
        or event.get("previous_event_sha256") != reanchor.get("event_sha256")
    ):
        errors.append("FP022 start gate READY event lineage differs")
    errors.extend(
        _validate_fp022_control_reanchor_seq68(
            root,
            event=reanchor,
            checkpoint=checkpoint,
            history=history,
        )
    )

    supersession = reanchor.get("contract_supersession")
    binding = (
        supersession.get("replacement_contract_binding")
        if isinstance(supersession, dict)
        else None
    )
    if (
        not isinstance(binding, dict)
        or set(binding) != FP008_START_GATE_CONTRACT_BINDING_FIELDS
    ):
        return errors + ["FP022 start gate contract binding differs"], [], {}, ready
    expected_identity = (
        ("schema version", "schema_version", "1.1"),
        (
            "document ID",
            "document_id",
            "WS-FP022-INITIAL-START-GATE-CONTRACT-20260814-002",
        ),
        ("path", "path", FP022_START_GATE_CONTRACT_PATH),
        ("contract ID", "contract_id", "WS-FP022-INTERNAL-START-GATE-R002"),
        ("contract version", "contract_version", "2026-08-14.1"),
    )
    for label, key, expected in expected_identity:
        _require_equal(
            errors,
            f"FP022 start gate contract {label}",
            binding.get(key),
            expected,
        )
    if _contains_symlink(root, FP022_START_GATE_CONTRACT_PATH):
        errors.append("FP022 start gate contract path contains a symlink")
        return errors, [], binding, ready
    path = resolve_repo_file(root, binding.get("path"))
    if path is None:
        return errors + ["FP022 start gate contract path is missing or unsafe"], [], binding, ready
    file_sha256 = binding.get("file_sha256")
    if not isinstance(file_sha256, str) or not SHA256_RE.fullmatch(file_sha256):
        errors.append("FP022 start gate contract file SHA-256 is invalid")
    elif sha256_file(path) != file_sha256:
        errors.append("FP022 start gate contract file SHA-256 differs")
    try:
        contract_value = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        return errors + [f"FP022 start gate contract cannot be loaded: {exc}"], [], binding, ready
    if set(contract_value) != {
        "schema_version",
        "document_id",
        "contract_id",
        "contract_version",
        "target_goal_id",
        "target_goal_content_sha256",
        "gate_purpose",
        "successor_reason_code",
        "supersedes",
        "ordered_checks",
        "claim_boundary",
    }:
        errors.append("FP022 start gate contract field set differs")
    _require_equal(
        errors,
        "FP022 start gate canonical contract SHA-256",
        binding.get("canonical_contract_sha256"),
        canonical_json_sha256(contract_value),
    )
    for label, actual, expected in (
        ("schema version", contract_value.get("schema_version"), "1.1"),
        ("document ID", contract_value.get("document_id"), binding.get("document_id")),
        ("contract ID", contract_value.get("contract_id"), binding.get("contract_id")),
        ("contract version", contract_value.get("contract_version"), binding.get("contract_version")),
        ("target Goal", contract_value.get("target_goal_id"), FP022_GOAL_ID),
        ("target Goal content SHA-256", contract_value.get("target_goal_content_sha256"), FP022_GOAL_SHA256),
        ("purpose", contract_value.get("gate_purpose"), "INITIAL_START"),
        (
            "successor reason",
            contract_value.get("successor_reason_code"),
            "SEQ67_READY_TRUST_ANCHOR_AND_CURRENT_CONTROL_COHORT_REQUIRED",
        ),
    ):
        _require_equal(errors, f"FP022 start gate contract {label}", actual, expected)
    raw_checks = contract_value.get("ordered_checks")
    checks: list[dict[str, str]] = []
    if not isinstance(raw_checks, list):
        errors.append("FP022 start gate ordered checks are missing")
    else:
        for index, item in enumerate(raw_checks, start=1):
            if (
                not isinstance(item, dict)
                or set(item) != {"check_id", "command"}
                or not isinstance(item.get("check_id"), str)
                or not isinstance(item.get("command"), str)
                or not item["command"]
            ):
                errors.append(f"FP022 start gate ordered check {index} differs")
                continue
            checks.append({"check_id": item["check_id"], "command": item["command"]})
    _require_equal(
        errors,
        "FP022 start gate ordered check IDs",
        [item["check_id"] for item in checks],
        FP022_START_GATE_CHECK_IDS,
    )
    forbidden = (
        "apps/web",
        "adminapp",
        "android-gateway",
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
        errors.append("FP022 start gate contract contains forbidden command scope")
    return errors, checks, binding, ready


def _validate_fp022_runtime_bindings(
    root: Path,
    value: Any,
    *,
    event_id: str,
) -> list[str]:
    return _validate_start_gate_runtime_bindings(
        root,
        value,
        event_id=event_id,
        expected_paths=FP022_START_GATE_RUNTIME_PATHS,
        label="FP022",
    )


def _npc_single_admin_recovery_start_gate_contract(
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
    if not isinstance(history, list) or not isinstance(event_sequence, int):
        return ["NPC start gate history is missing"], [], {}, {}

    ready_events = [
        item
        for item in history
        if isinstance(item, dict)
        and item.get("event_type") == "GOAL_READY"
        and item.get("subject_goal_id") == NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
        and isinstance(item.get("sequence"), int)
        and item["sequence"] < event_sequence
    ]
    if len(ready_events) != 1:
        return ["NPC start gate READY event is missing or ambiguous"], [], {}, {}
    ready = ready_events[0]
    reanchor_events = [
        item
        for item in history
        if isinstance(item, dict)
        and item.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and item.get("subject_goal_id") == NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
        and isinstance(item.get("sequence"), int)
        and item["sequence"] < event_sequence
    ]
    if len(reanchor_events) != 2:
        return errors + ["NPC start-control reanchor is missing or ambiguous"], [], {}, ready
    reanchor_event, correction_event = reanchor_events
    if (
        ready.get("sequence") != 57
        or ready.get("event_id") != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("focus_goal_content_sha256")
        != NPC_SINGLE_ADMIN_RECOVERY_GOAL_SHA256
        or ready.get("event_sha256")
        != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256
        or ready.get("event_sha256") != event_sha256(ready)
        or ready.get("implementation_start_gate_contract_binding")
        != NPC_SINGLE_ADMIN_RECOVERY_R001_BINDING
    ):
        errors.append("NPC start gate seq57 READY lineage differs")
    if (
        reanchor_event.get("sequence") != 58
        or reanchor_event.get("event_id")
        != NPC_SINGLE_ADMIN_RECOVERY_CONTROL_REANCHOR_EVENT_ID
        or reanchor_event.get("previous_event_sha256")
        != NPC_SINGLE_ADMIN_RECOVERY_READY_EVENT_SHA256
        or reanchor_event.get("event_sha256") != event_sha256(reanchor_event)
    ):
        errors.append("NPC start gate seq58 reanchor lineage differs")
    if (
        correction_event.get("sequence") != 59
        or correction_event.get("event_id")
        != NPC_SINGLE_ADMIN_RECOVERY_CONTROL_CORRECTION_EVENT_ID
        or correction_event.get("previous_event_sha256")
        != reanchor_event.get("event_sha256")
        or correction_event.get("event_sha256") != event_sha256(correction_event)
        or event.get("previous_event_sha256")
        != correction_event.get("event_sha256")
    ):
        errors.append("NPC start gate seq59 correction lineage differs")
    for item_label, reanchor_item in (
        ("seq58", reanchor_event),
        ("seq59", correction_event),
    ):
        supersession = reanchor_item.get("contract_supersession")
        replacement = (
            supersession.get("replacement_contract_binding")
            if isinstance(supersession, dict)
            else None
        )
        if replacement != NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING:
            errors.append(f"NPC start gate {item_label} R002 replacement binding differs")

    contract_errors, checks, _ = (
        _load_npc_single_admin_recovery_r002_contract(root)
    )
    errors.extend(contract_errors)
    return errors, checks, NPC_SINGLE_ADMIN_RECOVERY_R002_BINDING, ready


def _validate_npc_single_admin_recovery_runtime_bindings(
    root: Path,
    value: Any,
    *,
    event_id: str,
) -> list[str]:
    return _validate_start_gate_runtime_bindings(
        root,
        value,
        event_id=event_id,
        expected_paths=NPC_SINGLE_ADMIN_RECOVERY_START_GATE_RUNTIME_PATHS,
        label="NPC single-admin recovery",
    )


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
    fp022_scoped = (
        event.get("event_type") == "GOAL_STARTED"
        and subject == FP022_GOAL_ID
    )
    npc_scoped = (
        event.get("event_type") == "GOAL_STARTED"
        and subject == NPC_SINGLE_ADMIN_RECOVERY_GOAL_ID
    )
    private_scoped = fp008_scoped or fp046_scoped or fp022_scoped or npc_scoped
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
    elif fp022_scoped:
        contract_errors, checks, private_contract_binding, private_ready = (
            _fp022_start_gate_contract(
                root,
                event=event,
                checkpoint=checkpoint,
            )
        )
        errors.extend(contract_errors)
        expected_receipt_fields = FP008_START_GATE_RECEIPT_FIELDS
        expected_schema_version = "1.1"
    elif npc_scoped:
        contract_errors, checks, private_contract_binding, private_ready = (
            _npc_single_admin_recovery_start_gate_contract(
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
        private_label = (
            "FP008"
            if fp008_scoped
            else "FP046"
            if fp046_scoped
            else "FP022"
            if fp022_scoped
            else "NPC single-admin recovery"
        )
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
            if fp046_scoped
            else _validate_fp022_runtime_bindings
            if fp022_scoped
            else _validate_npc_single_admin_recovery_runtime_bindings
        )
        errors.extend(
            runtime_validator(
                root,
                receipt.get("runtime_bindings"),
                event_id=event_id,
            )
        )
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
    if event_type == "CANONICAL_BINDINGS_UPDATED":
        if _is_dependency_closure_event(event_type, event):
            return _dependency_closure_status_changes(event), None
        return {}, None
    if event_type in {
        "PACKAGE_ACTIVATED",
        "GOAL_START_CONTROL_REANCHORED",
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


def _dependency_closure_status_changes(
    event: Mapping[str, Any],
) -> dict[str, str] | None:
    """Return the only legal generic status shape before replay semantics."""

    subject = event.get("subject_goal_id")
    changes = event.get("status_changes")
    disposition = event.get("impact_disposition_by_goal")
    if (
        not all(field in event for field in GENERIC_DEPENDENCY_CLOSURE_DIRECT_FIELDS)
        or not isinstance(subject, str)
        or not isinstance(changes, dict)
        or set(changes) != {subject}
        or not isinstance(changes.get(subject), str)
        or not isinstance(disposition, dict)
        or disposition.get(subject)
        != {"result": "REOPEN_CONTAINER", "target_status": changes[subject]}
    ):
        return None
    return dict(changes)


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
    if event_type == "GOAL_START_CONTROL_REANCHORED":
        if not isinstance(subject, str):
            return None
        status = statuses.get(subject, "")
        return status, status
    if event_type == "CANONICAL_BINDINGS_UPDATED":
        producer = event.get("produced_by_goal_id")
        focus = event.get("focus_goal_id")
        if _is_dependency_closure_event(event_type, event):
            if not isinstance(subject, str):
                return None
            return statuses.get(subject, ""), changes.get(subject, "")
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


def _completion_source_is_allowed(
    before_status: object,
    goal_kind: object,
) -> bool:
    """Allow READY completion only for aggregate Workstream Goals."""

    return before_status == "IN_PROGRESS" or (
        before_status == "READY" and goal_kind == "WORKSTREAM"
    )


def _validate_r008_transition_review_binding(
    root: Path,
    event: Mapping[str, Any],
    suffix: list[dict[str, Any]],
) -> list[str]:
    """Bind seq72 to the actual approved transition-review triplet."""

    try:
        from scripts import (  # noqa: E402
            apply_walksafe_fp046_npc_r002_reopen_20260815 as review,
        )
        review_paths = {
            "assignment": review.TRANSITION_ASSIGNMENT_REL,
            "review_result": review.TRANSITION_RESULT_REL,
            "independent_review": review.TRANSITION_INDEPENDENT_REL,
        }
        raw_by_path = {
            relative: review._safe_regular_bytes(
                root, relative, "transition review"
            )
            for relative in review_paths.values()
        }
        expected_binding = {
            role: review._binding(relative, raw_by_path[relative])
            for role, relative in review_paths.items()
        }
        errors = []
        if event.get("transition_review_binding") != expected_binding:
            errors.append(
                "FP046/NPC R002 seq72 transition review byte binding differs"
            )

        assignment_raw = raw_by_path[review.TRANSITION_ASSIGNMENT_REL]
        result_raw = raw_by_path[review.TRANSITION_RESULT_REL]
        independent_raw = raw_by_path[review.TRANSITION_INDEPENDENT_REL]
        assignment = review.strict_json_bytes(
            assignment_raw, "transition assignment"
        )
        result = review.strict_json_bytes(
            result_raw, "transition review result"
        )
        source_bindings = event.get("source_bindings")
        source_checkpoint = (
            source_bindings.get("checkpoint")
            if isinstance(source_bindings, dict)
            else None
        )
        if (
            not isinstance(source_checkpoint, dict)
            or set(source_checkpoint) != {"path", "sha256", "byte_length"}
            or source_checkpoint.get("path")
            != review.CHECKPOINT_REL.as_posix()
            or not SHA256_RE.fullmatch(str(source_checkpoint.get("sha256", "")))
            or not isinstance(source_checkpoint.get("byte_length"), int)
            or isinstance(source_checkpoint.get("byte_length"), bool)
            or source_checkpoint["byte_length"] <= 0
        ):
            raise ValueError("transition source checkpoint binding differs")

        def actual_binding(relative: Path) -> dict[str, Any]:
            raw = review._safe_regular_bytes(root, relative, "review subject")
            return review._binding(relative, raw)

        for relative, (digest, byte_length) in review.R007_REVIEW_PINS.items():
            binding = actual_binding(relative)
            if (
                binding["sha256"] != digest
                or binding["byte_length"] != byte_length
            ):
                raise ValueError(f"R007 review binding differs: {relative}")
        subject_paths = sorted(
            {
                *review.r029_bridge.CANONICAL_OUTPUT_PATHS,
                review.FP046_R002_REL,
                review.NPC_R002_REL,
                review.AUTHORIZATION_REL,
                review.INITIAL_START_GATE_CONTRACT_REL,
            }
        )
        package = {
            "source_checkpoint": copy.deepcopy(source_checkpoint),
            "r007_control_successor_review_bindings": [
                actual_binding(relative) for relative in review.R007_REVIEW_PINS
            ],
            "authorization": actual_binding(review.AUTHORIZATION_REL),
            "initial_start_gate_contract": actual_binding(
                review.INITIAL_START_GATE_CONTRACT_REL
            ),
            "staged_subject_bindings": [
                actual_binding(relative) for relative in subject_paths
            ],
            "preflight": {"events": suffix},
        }
        review.validate_transition_assignment_document(
            assignment, assignment_raw, package
        )
        review._validate_transition_result_document(
            result, result_raw, assignment, assignment_raw
        )
        expected_independent = review.build_transition_independent_review(
            assignment, assignment_raw, result, result_raw
        ).encode("utf-8")
        if independent_raw != expected_independent:
            raise ValueError("transition independent review differs")
    except Exception as exc:
        return [f"FP046/NPC R002 seq72 transition review differs: {exc}"]
    return errors


def _validate_r008_control_review_boundary(
    root: Path,
    event: Mapping[str, Any],
    checkpoint: Mapping[str, Any],
) -> list[str]:
    """Bind seq72 and its managed snapshot to the live validated R008 triad."""

    try:
        from scripts import (  # noqa: E402
            build_walksafe_fp022_completion_seq70_71_review_20260814 as review,
        )

        review.validated_control_successor_r008_context(root)
        path_by_role = {
            "assignment": Path(review.CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL),
            "review_result": Path(review.CONTROL_SUCCESSOR_R008_RESULT_REL),
            "independent_review": Path(
                review.CONTROL_SUCCESSOR_R008_INDEPENDENT_REL
            ),
        }
        if tuple(path_by_role.values()) != tuple(
            Path(path) for path in review.CONTROL_SUCCESSOR_R008_PATHS
        ):
            raise ValueError("R008 control review role paths differ")
        expected_binding = {
            role: review._binding(relative, review._raw(root, relative))
            for role, relative in path_by_role.items()
        }
    except Exception as exc:
        return [f"FP046/NPC R002 seq72 R008 control review differs: {exc}"]
    errors: list[str] = []
    if event.get("r008_control_review_binding") != expected_binding:
        errors.append(
            "FP046/NPC R002 seq72 R008 control review byte binding differs"
        )
    state = checkpoint.get("working_tree_snapshot")
    managed = state.get("managed_changed_paths") if isinstance(state, dict) else None
    expected_paths = {
        relative.as_posix() for relative in path_by_role.values()
    }
    if not isinstance(managed, list) or not expected_paths.issubset(managed):
        errors.append(
            "FP046/NPC R002 seq72 R008 control review managed paths differ"
        )
    return errors


def validate_fp046_npc_r002_seq72_boundary(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> list[str]:
    """Fail closed on seq72's reviewed R029 canonical transition boundary."""

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(history, list) or len(history) < 72:
        return []
    if not isinstance(history[70], dict) or not isinstance(history[71], dict):
        return ["FP046/NPC R002 seq72 source or event is malformed"]
    source, event = history[70], history[71]
    errors: list[str] = []
    if (
        source.get("sequence") != 71
        or source.get("event_id") != FP022_COMPLETION_EVENT_ID
        or source.get("event_type") != "GOAL_COMPLETED"
        or source.get("subject_goal_id") != FP022_GOAL_ID
    ):
        errors.append("FP046/NPC R002 seq72 source seq71 differs")
    if (
        event.get("sequence") != 72
        or event.get("event_id") != R008_SEQ72_EVENT_ID
        or event.get("event_type") != "CANONICAL_BINDINGS_UPDATED"
    ):
        errors.append("FP046/NPC R002 seq72 event identity differs")

    source_canonical = source.get("canonical_binding_snapshot_after")
    if not isinstance(source_canonical, dict):
        errors.append("FP046/NPC R002 seq72 source canonical snapshot differs")
        expected_canonical: dict[str, Any] = {}
    else:
        expected_canonical = copy.deepcopy(source_canonical)
        expected_canonical.update(
            {
                "IMPLEMENTATION_GAP": R008_R029_CANONICAL_GAP_BINDING,
                "IMPLEMENTATION_BACKLOG": (
                    R008_R029_CANONICAL_BACKLOG_BINDING
                ),
            }
        )
    canonical = event.get("canonical_binding_snapshot_after")
    if canonical != expected_canonical:
        errors.append("FP046/NPC R002 seq72 canonical R029 snapshot differs")

    for role, expected_binding in (
        ("IMPLEMENTATION_GAP", R008_R029_CANONICAL_GAP_BINDING),
        ("IMPLEMENTATION_BACKLOG", R008_R029_CANONICAL_BACKLOG_BINDING),
    ):
        binding = canonical.get(role) if isinstance(canonical, dict) else None
        relative = expected_binding["path"]
        if binding != expected_binding:
            errors.append(
                f"FP046/NPC R002 seq72 canonical binding differs: {role}"
            )
        source_bindings = event.get("source_bindings")
        source_key = "r029_gap" if role == "IMPLEMENTATION_GAP" else "r029_backlog"
        source_binding = (
            source_bindings.get(source_key)
            if isinstance(source_bindings, dict)
            else None
        )
        path = resolve_repo_file(root, relative)
        if path is None:
            errors.append(
                f"FP046/NPC R002 seq72 canonical {role} is missing or unsafe"
            )
            continue
        expected_source_binding = {
            "path": relative,
            "sha256": expected_binding["file_sha256"],
            "byte_length": path.stat().st_size,
        }
        if source_binding != expected_source_binding:
            errors.append(
                f"FP046/NPC R002 seq72 source {source_key} binding differs"
            )
        if (
            _contains_symlink(root, relative)
            or sha256_file(path) != expected_binding["file_sha256"]
        ):
            errors.append(
                f"FP046/NPC R002 seq72 canonical {role} file differs"
            )

    suffix = [item for item in history[71:76] if isinstance(item, dict)]
    errors.extend(_validate_r008_transition_review_binding(root, event, suffix))
    errors.extend(
        _validate_r008_control_review_boundary(root, event, checkpoint)
    )
    return errors


def _legacy_backlog_upgrade_subjects(
    root: Path,
    graph: Any,
    before: Mapping[str, Mapping[str, Any]],
    after: Mapping[str, Mapping[str, Any]],
    subjects: dict[str, list[str]],
) -> dict[str, list[str]]:
    """Ignore only R028's exact legacy Backlog-provenance schema upgrade."""

    role = "IMPLEMENTATION_BACKLOG"
    ids = subjects.get(role)
    before_binding, after_binding = before.get(role), after.get(role)
    if (
        not isinstance(ids, list)
        or "*" not in ids
        or not isinstance(before_binding, dict)
        or not isinstance(after_binding, dict)
    ):
        return subjects
    if (
        before_binding != R008_R028_BACKLOG_BINDING
        or after_binding != R008_R029_CANONICAL_BACKLOG_BINDING
    ):
        return subjects
    paths = [
        resolve_repo_file(root, binding.get("path"))
        for binding in (before_binding, after_binding)
    ]
    if any(path is None for path in paths):
        return subjects
    try:
        if (
            sha256_file(paths[0]) != R008_R028_BACKLOG_BINDING["file_sha256"]
            or sha256_file(paths[1])
            != R008_R029_CANONICAL_BACKLOG_BINDING["file_sha256"]
        ):
            return subjects
        before_payload, after_payload = (load_json(path) for path in paths)
    except (OSError, ValueError, json.JSONDecodeError):
        return subjects
    if not isinstance(before_payload, dict) or not isinstance(after_payload, dict):
        return subjects
    try:
        scoped = graph.SCOPED_CONTENT_KEYS_BY_ROLE[role]
        global_keys = graph.GLOBAL_CONTENT_KEYS_BY_ROLE[role]
        normalize_residual = graph.normalized_role_residual_value
        normalize_payload = graph.normalized_normative_payload
    except (AttributeError, KeyError, TypeError):
        return subjects
    residual_keys = (
        set(before_payload)
        | set(after_payload)
    ) - set(scoped) - set(global_keys) - {"source_predecessor"}
    normalized_residual = lambda payload: normalize_payload(
        {
            key: normalize_residual(role, key, payload.get(key))
            for key in residual_keys
        }
    )
    if (
        before_payload.get("source_predecessor")
        != R008_R028_LEGACY_BACKLOG_SOURCE_PREDECESSOR
        or after_payload.get("source_predecessor")
        != R008_R029_NORMALIZED_BACKLOG_SOURCE_PREDECESSOR
        or normalized_residual(before_payload) != normalized_residual(after_payload)
        or normalize_payload(
            {key: before_payload.get(key) for key in global_keys}
        )
        != normalize_payload(
            {key: after_payload.get(key) for key in global_keys}
        )
    ):
        return subjects
    return {**subjects, role: [subject for subject in ids if subject != "*"]}


def _completion_archive_projection(
    active: Any,
    archived: Any,
    goal_id: str,
) -> tuple[dict[str, Any], dict[str, Any]] | None:
    if (
        not isinstance(active, dict)
        or not isinstance(archived, dict)
        or goal_id in archived
        or not isinstance(active.get(goal_id), list)
    ):
        return None
    return (
        {key: value for key, value in active.items() if key != goal_id},
        {**archived, goal_id: active[goal_id]},
    )


def _validate_dependency_closure_update(
    root: Path,
    *,
    label: str,
    graph: Any,
    event: Mapping[str, Any],
    bindings_before: Mapping[str, Mapping[str, Any]],
    nodes: Mapping[str, Mapping[str, Any]],
    statuses: Mapping[str, str],
    completion_bindings_by_goal: Mapping[str, Any],
    latest_start_event_by_goal: Mapping[str, Mapping[str, Any]],
    completion_hashes: Mapping[str, str],
    completion_times: Mapping[str, str],
    latest_completion: Any,
    latest_archived_completion: Any,
) -> tuple[list[str], dict[str, str], dict[str, dict[str, str]], dict[str, str]]:
    """Replay the direct-field seq72+ canonical dependency closure."""

    errors: list[str] = []
    if not all(field in event for field in GENERIC_DEPENDENCY_CLOSURE_DIRECT_FIELDS):
        errors.append(f"{label} canonical dependency closure fields are missing")
    after = event.get("canonical_binding_snapshot_after")
    if not isinstance(after, dict):
        return errors + [f"{label} canonical binding snapshot is malformed"], {}, {}, {}
    try:
        snapshot_errors, normalized_after = (
            graph.validate_canonical_binding_snapshot(
                root,
                after,
                label=f"{label} canonical binding snapshot",
            )
        )
        errors.extend(snapshot_errors)
        if normalized_after != after:
            errors.append(f"{label} canonical binding snapshot differs")
        changed_roles = sorted(
            role
            for role in set(bindings_before) | set(normalized_after)
            if bindings_before.get(role) != normalized_after.get(role)
        )
        if not changed_roles:
            errors.append(f"{label} canonical binding update is a no-op")
        if event.get("changed_binding_roles") != changed_roles:
            errors.append(f"{label} changed canonical binding roles differ")

        replay_nodes = {
            goal_id: node
            for goal_id, node in nodes.items()
            if goal_id in statuses
        }
        subject_errors, subjects = graph.canonical_changed_subject_ids_by_role(
            root,
            changed_roles=changed_roles,
            bindings_before=bindings_before,
            bindings_after=normalized_after,
        )
        if not subject_errors:
            subjects = _legacy_backlog_upgrade_subjects(
                root, graph, bindings_before, normalized_after, subjects
            )
        direct = graph.changed_binding_affected_goals(
            changed_roles=set(changed_roles),
            changed_subject_ids_by_role=subjects,
            nodes=replay_nodes,
            statuses=statuses,
            completion_bindings_by_goal=completion_bindings_by_goal,
            latest_start_event_by_goal=latest_start_event_by_goal,
        )
        impacted = graph.expand_affected_goal_dependency_closure(
            nodes=replay_nodes,
            statuses=statuses,
            directly_affected_goal_roles=direct,
        )
    except (ImportError, OSError, RuntimeError, TypeError, ValueError) as exc:
        return errors + [
            f"{label} canonical dependency closure cannot be derived: {exc}"
        ], {}, {}, {}
    errors.extend(f"{label}: {error}" for error in subject_errors)
    if event.get("changed_subject_ids_by_role") != subjects:
        errors.append(f"{label} canonical changed subject IDs differ")
    expected_dispositions: dict[str, dict[str, str]] = {}
    expected_changes: dict[str, str] = {}
    expected_reopened: dict[str, str] = {}
    pending: dict[str, dict[str, str]] = {}
    event_hash = event.get("event_sha256")
    for goal_id in sorted(impacted):
        node = replay_nodes.get(goal_id, {})
        status = statuses.get(goal_id)
        roles = impacted[goal_id]
        kind = node.get("goal_kind")
        if kind == "WORK_ITEM" and status == "COMPLETE_AT_TARGET":
            expected_dispositions[goal_id] = {"result": "REOPEN_REQUIRED"}
            trigger = {
                "canonical_update_event_sha256": event_hash,
                "target_completion_event_sha256": completion_hashes.get(goal_id),
                "target_completion_occurred_at": completion_times.get(
                    completion_hashes.get(goal_id)
                ),
                "decided_at": event.get("occurred_at"),
            }
            if all(isinstance(value, str) for value in trigger.values()) and SHA256_RE.fullmatch(str(event_hash)):
                pending[goal_id] = {key: str(value) for key, value in trigger.items()}
            else:
                errors.append(f"{label} canonical dependency closure seal is invalid")
        elif kind == "WORKSTREAM" and status == "COMPLETE_AT_TARGET":
            target = graph.workstream_reopen_target_status(roles)
            expected_dispositions[goal_id] = {
                "result": "REOPEN_CONTAINER",
                "target_status": target,
            }
            expected_changes[goal_id] = target
            completion_hash = completion_hashes.get(goal_id)
            if isinstance(completion_hash, str) and SHA256_RE.fullmatch(completion_hash):
                expected_reopened[goal_id] = completion_hash
            else:
                errors.append(f"{label} reopened Workstream completion binding is missing")
        else:
            errors.append(f"{label} closure target is not completed/reopenable: {goal_id}")
    if event.get("impact_closure_goal_ids") != sorted(impacted):
        errors.append(f"{label} canonical impact dependency closure differs")
    if event.get("impact_disposition_by_goal") != expected_dispositions:
        errors.append(f"{label} canonical change impact disposition set differs")
    if event.get("reopened_completion_event_sha256_by_goal") != expected_reopened:
        errors.append(f"{label} reopened Workstream completion binding differs")
    if event.get("status_changes") != expected_changes:
        errors.append(f"{label} canonical update Workstream reopen set differs")
    reopened_ids = [
        goal_id
        for goal_id, disposition in expected_dispositions.items()
        if disposition.get("result") == "REOPEN_CONTAINER"
    ]
    if (
        len(reopened_ids) != 1
        or event.get("subject_goal_id") != reopened_ids[0]
        or set(expected_changes) != {reopened_ids[0]}
    ):
        errors.append(f"{label} canonical update reopen subject differs")
    else:
        projection = _completion_archive_projection(
            latest_completion, latest_archived_completion, reopened_ids[0]
        )
        if projection is None:
            errors.append(f"{label} reopened Workstream evidence is missing")
        else:
            active, archived = projection
            if (
                event.get("completion_evidence_by_goal_after") != active
                or event.get("archived_completion_evidence_by_goal_after")
                != archived
            ):
                errors.append(f"{label} reopened Workstream archive differs")
    subject = event.get("subject_goal_id")
    completion_hash = expected_reopened.get(subject) if isinstance(subject, str) else None
    reopened = (
        {
            "subject_goal_id": subject,
            "canonical_update_event_sha256": event_hash,
            "archived_completion_event_sha256": completion_hash,
        }
        if isinstance(subject, str)
        and isinstance(event_hash, str)
        and isinstance(completion_hash, str)
        else {}
    )
    return errors, expected_changes, pending, reopened


def _validate_dependency_closure_successor(
    root: Path,
    *,
    label: str,
    graph: Any,
    event: Mapping[str, Any],
    before: Mapping[str, str],
    nodes: Mapping[str, Mapping[str, Any]],
    goal_paths: Mapping[str, str],
    bindings: Mapping[str, Mapping[str, Any]],
    pending: Mapping[str, Mapping[str, str]],
    rewritten_successors: Mapping[str, str],
    latest_completion: Any,
    latest_archived_completion: Any,
) -> list[str]:
    """Validate the one event that may clear a REOPEN_REQUIRED Work Item."""

    errors: list[str] = []
    subject = event.get("subject_goal_id")
    successor = event.get("materialized_goal_id")
    if not isinstance(subject, str) or not isinstance(successor, str):
        return [f"{label} canonical-change successor boundary differs"]
    expected_trigger = pending.get(subject)
    predecessor = nodes.get(subject, {})
    replacement = nodes.get(successor, {})
    if (
        not isinstance(expected_trigger, dict)
        or predecessor.get("goal_kind") != "WORK_ITEM"
        or replacement.get("goal_kind") != "WORK_ITEM"
    ):
        errors.append(f"{label} canonical-change successor boundary differs")
    if (
        not isinstance(expected_trigger, dict)
        or bool(set(expected_trigger) & set(event))
        or event.get("reopen_trigger") != expected_trigger
    ):
        errors.append(f"{label} canonical-change successor binding differs")
    if event.get("canonical_binding_snapshot_after") != bindings:
        errors.append(f"{label} successor canonical binding snapshot differs")
    try:
        source = graph.materialization_source_snapshot(dict(replacement))
        dependencies = set(graph.string_list(predecessor.get("start_requires"))) | set(
            graph.string_list(predecessor.get("completion_requires"))
        )
        expected_start = [
            rewritten_successors.get(goal_id, goal_id)
            for goal_id in graph.string_list(predecessor.get("start_requires"))
        ]
        expected_completion = [
            rewritten_successors.get(goal_id, goal_id)
            for goal_id in graph.string_list(predecessor.get("completion_requires"))
        ]
        parent_id = replacement.get("parent_goal_id")
        if (
            not graph.successor_semantic_scope_matches(
                dict(predecessor), dict(replacement)
            )
            or dependencies & set(pending)
            or replacement.get("start_requires") != expected_start
            or replacement.get("completion_requires") != expected_completion
            or not graph.successor_parent_accepts_revision(
                before.get(str(parent_id)), canonical_change_successor=True
            )
        ):
            errors.append(f"{label} successor semantic/dependency contract differs")
    except (ImportError, OSError, RuntimeError, TypeError, ValueError) as exc:
        return errors + [f"{label} successor contract cannot be derived: {exc}"]
    subject_path = resolve_repo_file(root, goal_paths.get(subject))
    successor_path = resolve_repo_file(root, goal_paths.get(successor))
    predecessor_id = replacement.get("predecessor_goal_id")
    predecessor_relative = (
        goal_paths.get(predecessor_id)
        if isinstance(predecessor_id, str)
        else None
    )
    predecessor_path = resolve_repo_file(root, predecessor_relative)
    subject_sha = sha256_file(subject_path) if subject_path is not None else None
    successor_sha = sha256_file(successor_path) if successor_path is not None else None
    predecessor_sha = (
        sha256_file(predecessor_path)
        if predecessor_path is not None
        and not _contains_symlink(root, predecessor_relative)
        else None
    )
    if (
        predecessor_sha is None
        or replacement.get("predecessor_goal_content_sha256")
        != predecessor_sha
        or event.get("predecessor_goal_content_sha256") != predecessor_sha
    ):
        errors.append(f"{label} successor predecessor live binding differs")
    materialization = {
        "artifact_trigger_evidence_refs": replacement.get(
            "artifact_trigger_evidence_refs"
        ),
        "artifact_work_reason": replacement.get("artifact_work_reason"),
        "materialized_goal_id": successor,
        "materialized_goal_path": goal_paths.get(successor),
        "materialized_goal_content_sha256": successor_sha,
        "predecessor_goal_id": predecessor_id,
        "predecessor_goal_content_sha256": predecessor_sha,
        "supersedes_goal_id": subject,
        "supersedes_goal_content_sha256": subject_sha,
    }
    if (
        any(event.get(field) != value for field, value in materialization.items())
        or replacement.get("supersedes_goal_id") != subject
        or replacement.get("supersedes_goal_content_sha256") != subject_sha
        or source != graph.materialization_source_snapshot(dict(event))
        or bindings.get(str(source.get("role"))) != source
        or replacement.get("reopen_reason") != "CANONICAL_INPUT_CHANGED"
        or event.get("evidence_refs") != []
    ):
        errors.append(f"{label} successor source/lineage differs")
    projection = _completion_archive_projection(
        latest_completion, latest_archived_completion, subject
    )
    if projection is None:
        errors.append(f"{label} superseded completion evidence is missing")
    elif event.get("completion_evidence_by_goal_after") != projection[0] or event.get(
        "archived_completion_evidence_by_goal_after"
    ) != projection[1]:
        errors.append(f"{label} successor completion evidence archive differs")
    if (
        "dynamic_goal_inventory_after" in event
        or "materialized_child_goal_ids_by_parent_after" in event
    ):
        errors.append(f"{label} successor inventory projection is self-referential")
    return errors


def _validate_dependency_closure_inventory_projection(
    root: Path,
    *,
    label: str,
    event: Mapping[str, Any],
    nodes: Mapping[str, Mapping[str, Any]],
    goal_paths: Mapping[str, str],
    latest_inventory: Any,
    latest_children: Any,
    successors: Mapping[str, str],
    reopened_closure: Mapping[str, str],
) -> list[str]:
    """Bind deferred successor inventory after its event hashes exist."""

    errors: list[str] = []
    parents = {
        parent
        for successor in successors
        if isinstance((node := nodes.get(successor)), Mapping)
        and isinstance((parent := node.get("parent_goal_id")), str)
    }
    parent = next(iter(parents)) if len(parents) == 1 else None
    if (
        not isinstance(parent, str)
        or event.get("subject_goal_id") != parent
        or reopened_closure.get("subject_goal_id") != parent
    ):
        errors.append(f"{label} successor inventory projection subject differs")
    if not isinstance(latest_inventory, dict) or not isinstance(latest_children, dict):
        return errors + [f"{label} successor inventory projection state is malformed"]
    expected_basis = {
        "mode": "CANONICAL_DEPENDENCY_CLOSURE_REOPEN",
        "canonical_update_event_sha256": reopened_closure.get(
            "canonical_update_event_sha256"
        ),
        "successor_event_sha256_by_goal": dict(sorted(successors.items())),
        "archived_completion_event_sha256": reopened_closure.get(
            "archived_completion_event_sha256"
        ),
    }
    if event.get("readiness_basis") != expected_basis:
        errors.append(f"{label} reopened Workstream readiness basis differs")
    inventory = copy.deepcopy(latest_inventory)
    children = copy.deepcopy(latest_children)
    for successor, event_hash in successors.items():
        node = nodes.get(successor, {})
        path = goal_paths.get(successor)
        resolved = resolve_repo_file(root, path)
        if (
            node.get("goal_kind") != "WORK_ITEM"
            or not isinstance(path, str)
            or resolved is None
            or successor in inventory
        ):
            errors.append(f"{label} successor dynamic Goal inventory binding differs")
            continue
        record = {
            **{
                field: node.get(field)
                for field in (
                    "artifact_trigger_evidence_refs", "artifact_work_reason", "initial_status",
                    "work_item_type", "parent_goal_id", "materialized_from_role",
                    "materialized_from_path", "materialized_from_document_id",
                    "materialized_from_sha256", "predecessor_goal_id",
                    "predecessor_goal_content_sha256", "supersedes_goal_id",
                    "supersedes_goal_content_sha256",
                )
            },
            "goal_id": successor, "path": path, "sha256": sha256_file(resolved),
            "goal_kind": "WORK_ITEM", "materialized_event_sha256": event_hash,
        }
        parent = node.get("parent_goal_id")
        members = children.get(parent) if isinstance(parent, str) else None
        valid_members = (
            isinstance(members, list)
            and all(isinstance(member, str) for member in members)
            and len(members) == len(set(members))
        )
        if (
            not isinstance(parent, str)
            or not valid_members
            or successor in members
        ):
            errors.append(f"{label} successor materialized child map differs")
        else:
            inventory[successor] = record
            children[parent] = sorted(set(members) | {successor})
    if event.get("dynamic_goal_inventory_after") != inventory:
        errors.append(f"{label} successor dynamic Goal inventory differs")
    if event.get("materialized_child_goal_ids_by_parent_after") != children:
        errors.append(f"{label} successor materialized child map differs")
    return errors


def _validate_reopened_successor_ready(
    *,
    label: str,
    event: Mapping[str, Any],
    before: Mapping[str, str],
    nodes: Mapping[str, Mapping[str, Any]],
    container_ready_events: Mapping[str, str],
) -> list[str]:
    subject = event.get("subject_goal_id")
    if not isinstance(subject, str) or subject not in container_ready_events:
        return []
    parent = nodes.get(subject, {}).get("parent_goal_id")
    if (
        not isinstance(parent, str)
        or before.get(parent) != "READY"
        or event.get("reopened_container_ready_event_sha256")
        != container_ready_events[subject]
    ):
        return [f"{label} reopened Work Item container readiness differs"]
    return []


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
            str(event_type), event, before, changes
        )
        if expected_boundary is None:
            errors.append(f"{label} from/to boundary cannot be derived")
        elif (
            event.get("from_status"),
            event.get("to_status"),
        ) != expected_boundary:
            errors.append(f"{label} from/to boundary differs")
        if event_type == "GOAL_START_CONTROL_REANCHORED":
            if (
                not isinstance(subject, str)
                or before.get(subject) != "READY"
                or any(status == "IN_PROGRESS" for status in before.values())
            ):
                errors.append(f"{label} does not reanchor a sole READY Goal")
        elif event_type == "GOAL_STARTED":
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
            closure_event = _is_dependency_closure_event(event_type, event)
            if (
                isinstance(event.get("sequence"), int)
                and event["sequence"] >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE
                and not closure_event
                and "subject_goal_id" in event
            ):
                errors.append(
                    f"{label} dependency closure producer control differs"
                )
            elif (
                isinstance(event.get("sequence"), int)
                and event["sequence"] >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE
                and not closure_event
                and (not isinstance(producer, str) or not producer)
            ):
                errors.append(f"{label} producer control is missing")
            elif not closure_event and producer not in (None, ""):
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
                or before.get(subject) not in {"IN_PROGRESS", "READY"}
            ):
                errors.append(
                    f"{label} does not complete IN_PROGRESS or READY work"
                )
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
    errors.extend(validate_fp046_npc_r002_seq72_boundary(root, checkpoint))
    prepared = history[0] if isinstance(history[0], dict) else {}
    archived_statuses = archived_state.get("status_by_goal")
    if not isinstance(archived_statuses, dict):
        return errors + ["v2.3 archive status map is missing"]
    archived_bindings = canonical_binding_snapshot(archive)
    imported = state.get("imported_predecessor_goal_bindings")
    if not isinstance(imported, dict):
        imported = {}
        errors.append("v2.4 imported predecessor Goal bindings are missing")

    goal_kind_by_id: dict[str, object] = {
        goal_id: record.get("goal_kind")
        for goal_id, record in imported.items()
        if isinstance(goal_id, str) and isinstance(record, dict)
    }
    final_inventory = state.get("dynamic_goal_inventory")
    if isinstance(final_inventory, dict):
        for goal_id, record in final_inventory.items():
            if isinstance(goal_id, str) and isinstance(record, dict):
                goal_kind_by_id[goal_id] = record.get("goal_kind")

    closure_graph: Any = None
    closure_nodes: dict[str, dict[str, Any]] = {}
    latest_start_event_by_goal: dict[str, dict[str, Any]] = {}
    if len(history) >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE:
        try:
            closure_graph = _load_frozen_v23_utility(
                root,
                relative="scripts/check_walksafe_goal_graph_v2_3.py",
                module_name="_walksafe_v23_goal_graph_utility_for_v24",
            )
            node_errors, closure_nodes = closure_graph.current_goal_nodes(root, state)
            errors.extend(f"v2.4 dependency closure: {error}" for error in node_errors)
            for goal_id, node in closure_nodes.items():
                goal_kind_by_id[goal_id] = node.get("goal_kind")
        except (OSError, RuntimeError, TypeError, ValueError) as exc:
            errors.append(f"v2.4 dependency closure Goal nodes cannot be loaded: {exc}")

    previous_hash = ""
    statuses: dict[str, str] = {}
    completion_hashes = _completion_hash_seed(archive)
    completion_time_by_hash = {
        event.get("event_sha256"): event.get("occurred_at")
        for source_history in (archived_state.get("transition_history"), history)
        if isinstance(source_history, list)
        for event in source_history
        if isinstance(event, dict)
        and isinstance(event.get("event_sha256"), str)
        and isinstance(event.get("occurred_at"), str)
    }
    pending_producer: str | None = None
    pending_reopen_source_event_by_goal: dict[str, dict[str, str]] = {}
    successor_goal_id_by_superseded: dict[str, str] = {}
    pending_successor_inventory_event_by_goal: dict[str, str] = {}
    pending_reopened_closure: dict[str, str] = {}
    reopened_container_ready_by_successor: dict[str, str] = {}
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
        strict_projection_boundary = (
            index >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE
        )
        closure_event = False
        closure_successor_event = False
        dependency_inventory_projection = False
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
        if strict_projection_boundary:
            unknown_after = sorted(
                field
                for field in event
                if field.endswith("_after")
                and field not in GENERIC_AFTER_PROJECTION_FIELDS
            )
            if unknown_after:
                errors.append(
                    f"{label} top-level after projection fields are not allowed: "
                    + ", ".join(unknown_after)
                )
            runtime_projection = event.get("runtime_after")
            if (
                isinstance(runtime_projection, dict)
                and set(runtime_projection) != GENERIC_RUNTIME_AFTER_FIELDS
            ):
                errors.append(f"{label} runtime_after field set differs")
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
            closure_successor_event = (
                event_type == "GOAL_SUPERSEDED"
                and isinstance(subject, str)
                and subject in pending_reopen_source_event_by_goal
            )
            dependency_inventory_projection = (
                event_type == "GOAL_READY"
                and bool(pending_successor_inventory_event_by_goal)
            )
            if pending_producer is not None and not (
                event_type == "GOAL_COMPLETED"
                and subject == pending_producer
            ):
                errors.append(
                    f"{label} violates canonical producer completion order"
                )
            if pending_reopen_source_event_by_goal and not (
                event_type == "GOAL_SUPERSEDED"
                and isinstance(subject, str)
                and subject in pending_reopen_source_event_by_goal
            ):
                errors.append(f"{label} unresolved canonical-change Goals must be superseded before another event")
            elif pending_successor_inventory_event_by_goal and not (
                event_type == "GOAL_READY"
                or (event_type == "GOAL_SUPERSEDED" and pending_reopen_source_event_by_goal)
            ):
                errors.append(f"{label} successor inventory projection must be the next ready event")
            closure_event = _is_dependency_closure_event(event_type, event)
            if closure_event:
                if not closure_nodes or closure_graph is None:
                    errors.append(f"{label} canonical dependency closure Goal nodes are missing")
                    expected_changes = {}
                    pending_reopen = {}
                else:
                    (
                        closure_errors,
                        expected_changes,
                        pending_reopen,
                        pending_reopened_closure,
                    ) = (
                        _validate_dependency_closure_update(
                            root,
                            label=label,
                            graph=closure_graph,
                            event=event,
                            bindings_before=latest_canonical_bindings,
                            nodes=closure_nodes,
                            statuses=statuses,
                            completion_bindings_by_goal=latest_completion_evidence,
                            latest_start_event_by_goal=(
                                latest_start_event_by_goal
                            ),
                            completion_hashes=completion_hashes,
                            completion_times=completion_time_by_hash,
                            latest_completion=latest_completion_evidence,
                            latest_archived_completion=(
                                latest_archived_completion_evidence
                            ),
                        )
                    )
                    errors.extend(closure_errors)
                pending_reopen_source_event_by_goal.update(pending_reopen)
                transition_error = None
            else:
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
                str(event_type), event, before, changes
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
            elif event_type == "GOAL_START_CONTROL_REANCHORED":
                errors.extend(
                    _validate_npc_single_admin_recovery_control_reanchor(
                        root,
                        event=event,
                        checkpoint=checkpoint,
                        history=history,
                    )
                )
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
                if (
                    isinstance(event.get("sequence"), int)
                    and event["sequence"]
                    >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE
                    and not closure_event
                    and "subject_goal_id" in event
                ):
                    errors.append(
                        f"{label} dependency closure producer control differs"
                    )
                elif (
                    isinstance(event.get("sequence"), int)
                    and event["sequence"]
                    >= GENERIC_DEPENDENCY_CLOSURE_FIRST_SEQUENCE
                    and not closure_event
                    and (not isinstance(producer, str) or not producer)
                ):
                    errors.append(f"{label} producer control is missing")
                elif not closure_event and producer not in (None, ""):
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
                    or not _completion_source_is_allowed(
                        before.get(subject),
                        goal_kind_by_id.get(subject),
                    )
                ):
                    errors.append(
                        f"{label} does not complete IN_PROGRESS work or a READY Workstream"
                    )
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
                if pending_successor_inventory_event_by_goal:
                    errors.extend(
                        _validate_dependency_closure_inventory_projection(
                            root,
                            label=label,
                            event=event,
                            nodes=closure_nodes,
                            goal_paths=goal_paths,
                            latest_inventory=latest_inventory,
                            latest_children=latest_children,
                            successors=(
                                pending_successor_inventory_event_by_goal
                            ),
                            reopened_closure=pending_reopened_closure,
                        )
                    )
                    event_hash = event.get("event_sha256")
                    if isinstance(event_hash, str):
                        reopened_container_ready_by_successor.update(
                            {
                                goal_id: event_hash
                                for goal_id in (
                                    pending_successor_inventory_event_by_goal
                                )
                            }
                        )
                    pending_successor_inventory_event_by_goal.clear()
                    pending_reopened_closure = {}
                else:
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
                errors.extend(
                    _validate_reopened_successor_ready(
                        label=label,
                        event=event,
                        before=before,
                        nodes=closure_nodes,
                        container_ready_events=(
                            reopened_container_ready_by_successor
                        ),
                    )
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
                if isinstance(subject, str) and subject in pending_reopen_source_event_by_goal:
                    errors.extend(
                        _validate_dependency_closure_successor(
                            root,
                            label=label,
                            graph=closure_graph,
                            event=event,
                            before=before,
                            nodes=closure_nodes,
                            goal_paths=goal_paths,
                            bindings=latest_canonical_bindings,
                            pending=pending_reopen_source_event_by_goal,
                            rewritten_successors=successor_goal_id_by_superseded,
                            latest_completion=latest_completion_evidence,
                            latest_archived_completion=(
                                latest_archived_completion_evidence
                            ),
                        )
                    )
                    pending_reopen_source_event_by_goal.pop(subject, None)
                    if isinstance(materialized, str):
                        successor_goal_id_by_superseded[subject] = materialized
                        event_hash = event.get("event_sha256")
                        if isinstance(event_hash, str) and SHA256_RE.fullmatch(event_hash):
                            pending_successor_inventory_event_by_goal[materialized] = event_hash
                        else:
                            errors.append(f"{label} successor event seal is invalid")
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
                canonical_update = index == 1 or event_type == "CANONICAL_BINDINGS_UPDATED"
                if (
                    strict_projection_boundary
                    and not canonical_update
                    and candidate_bindings != latest_canonical_bindings
                ):
                    errors.append(
                        f"{label} event type cannot change canonical bindings"
                    )
                elif canonical_update or not strict_projection_boundary:
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
        completion_present = "completion_evidence_by_goal_after" in event
        archived_present = "archived_completion_evidence_by_goal_after" in event
        closure_projection = closure_event or closure_successor_event
        if event_type == "GOAL_COMPLETED":
            expected_completion = (
                {**latest_completion_evidence, subject: event.get("evidence_refs")}
                if isinstance(latest_completion_evidence, dict)
                and isinstance(subject, str)
                else None
            )
            if (
                not completion_present
                or event.get("completion_evidence_by_goal_after")
                != expected_completion
            ):
                errors.append(f"{label} completion evidence projection differs")
        elif (
            strict_projection_boundary
            and not closure_projection
            and completion_present
        ):
            errors.append(
                f"{label} event type cannot project completion evidence"
            )
        if (
            strict_projection_boundary
            and not closure_projection
            and archived_present
        ):
            errors.append(
                f"{label} event type cannot project archived completion evidence"
            )
        if closure_projection and completion_present != archived_present:
            errors.append(f"{label} completion archive projection is incomplete")
        if index == 1 or event_type == "GOAL_COMPLETED" or closure_projection:
            if completion_present:
                latest_completion_evidence = event.get(
                    "completion_evidence_by_goal_after"
                )
            if archived_present:
                latest_archived_completion_evidence = event.get(
                    "archived_completion_evidence_by_goal_after"
                )

        inventory_present = "dynamic_goal_inventory_after" in event
        children_present = "materialized_child_goal_ids_by_parent_after" in event
        if inventory_present != children_present:
            errors.append(f"{label} Goal inventory projection is incomplete")
        inventory_projection_allowed = (
            index == 1
            or event_type == "GOAL_READY"
            and dependency_inventory_projection
        )
        if (
            inventory_present
            and event_type == "GOAL_READY"
            and not dependency_inventory_projection
        ):
            inventory = event.get("dynamic_goal_inventory_after")
            children = event.get("materialized_child_goal_ids_by_parent_after")
            record = (
                inventory.get(subject)
                if isinstance(inventory, dict) and isinstance(subject, str)
                else None
            )
            parent = record.get("parent_goal_id") if isinstance(record, dict) else None
            members = (
                latest_children.get(parent)
                if isinstance(latest_children, dict) and isinstance(parent, str)
                else None
            )
            ordinary_projection_valid = (
                isinstance(latest_inventory, dict)
                and isinstance(latest_children, dict)
                and isinstance(inventory, dict)
                and isinstance(children, dict)
                and isinstance(record, dict)
                and isinstance(subject, str)
                and subject not in latest_inventory
                and inventory == {**latest_inventory, subject: record}
                and isinstance(parent, str)
                and isinstance(members, list)
                and subject not in members
                and children
                == {**latest_children, parent: [*members, subject]}
            )
            if strict_projection_boundary and not ordinary_projection_valid:
                errors.append(f"{label} ordinary Goal inventory projection differs")
            inventory_projection_allowed = True
        elif (
            strict_projection_boundary
            and inventory_present
            and not inventory_projection_allowed
        ):
            errors.append(f"{label} event type cannot project Goal inventory")
        if inventory_present and inventory_projection_allowed:
            latest_inventory = event.get("dynamic_goal_inventory_after")
            latest_children = event.get(
                "materialized_child_goal_ids_by_parent_after"
            )

        for event_field, current_value, state_name in (
            ("blockers_after", latest_blockers, "blocker map"),
            (
                "blocker_resolution_ids_after",
                latest_resolution_ids,
                "blocker resolution IDs",
            ),
        ):
            if event_field not in event:
                continue
            value = event.get(event_field)
            if (
                strict_projection_boundary
                and event_type not in {"BLOCKER_RECORDED", "BLOCKER_RESOLVED"}
                and value != current_value
            ):
                errors.append(f"{label} event type cannot change {state_name}")
                continue
            if event_field == "blockers_after":
                latest_blockers = value
            else:
                latest_resolution_ids = value
        if event_type in {"GOAL_STARTED", "WORK_SESSION_RESUMED"} and isinstance(subject, str):
            latest_start_event_by_goal[subject] = dict(event)
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

    if pending_reopen_source_event_by_goal:
        errors.append("v2.4 history ends with unresolved canonical-change Goals")
    if pending_successor_inventory_event_by_goal:
        errors.append("v2.4 history ends before successor inventory projection")
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


def _fp022_completion_review_paths() -> dict[str, str]:
    from scripts import (
        build_walksafe_fp022_completion_seq70_71_review_20260814 as review,
    )

    return {
        "assignment": review.ASSIGNMENT_REL.as_posix(),
        "review_result": review.RESULT_REL.as_posix(),
        "independent_review": review.INDEPENDENT_REL.as_posix(),
    }


def validate_fp022_completion_seq70_71(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    """Fail closed on the exact reviewed FP-022 producer/completion suffix."""
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(history, list) or len(history) < 70:
        return []
    errors: list[str] = []
    if len(history) < 71:
        return ["FP022 seq70 producer transaction lacks adjacent seq71 completion"]
    update, completion = history[69:71]
    if not isinstance(update, dict) or not isinstance(completion, dict):
        return ["FP022 seq70/71 events are malformed"]
    expected_update_fields = {
        "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
        "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
        "focus_goal_content_sha256", "from_status", "to_status",
        "static_plan_manifest_sha256", "status_changes", "runtime_after",
        "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
        "evidence_refs", "previous_event_sha256", "produced_by_goal_id",
        "produced_binding_roles", "producer_completion_receipt_binding",
        "changed_binding_roles", "changed_subject_ids_by_role",
        "producer_output_subject_ids_by_role", "impact_closure_goal_ids",
        "impact_disposition_by_goal", "reopened_completion_event_sha256_by_goal",
        "canonical_binding_snapshot_after", "transition_control_review_binding",
        "event_sha256",
    }
    expected_completion_fields = {
        "sequence", "event_id", "event_type", "occurred_on", "occurred_at",
        "previous_focus_goal_id", "previous_focus_content_sha256", "focus_goal_id",
        "focus_goal_content_sha256", "subject_goal_id", "from_status", "to_status",
        "static_plan_manifest_sha256", "status_changes", "runtime_after",
        "blockers_after", "blocker_resolution_ids_after", "source_checkpoint_version",
        "evidence_refs", "previous_event_sha256", "canonical_update_event_sha256",
        "completion_receipt_binding", "completion_evidence_bindings",
        "completion_evidence_by_goal_after", "canonical_binding_snapshot_after",
        "event_sha256",
    }
    expected_binding = {
        "role": FP022_COMPLETION_ROLE,
        "document_id": FP022_COMPLETION_DOCUMENT_ID,
        "path": FP022_COMPLETION_PATH,
    }
    source = history[68] if isinstance(history[68], dict) else {}
    for label, actual, expected in (
        ("seq70 fields", set(update), expected_update_fields),
        ("seq71 fields", set(completion), expected_completion_fields),
        ("seq70 sequence", update.get("sequence"), 70),
        ("seq71 sequence", completion.get("sequence"), 71),
        ("seq70 ID", update.get("event_id"), FP022_COMPLETION_UPDATE_EVENT_ID),
        ("seq71 ID", completion.get("event_id"), FP022_COMPLETION_EVENT_ID),
        ("seq70 type", update.get("event_type"), "CANONICAL_BINDINGS_UPDATED"),
        ("seq71 type", completion.get("event_type"), "GOAL_COMPLETED"),
        ("seq70 previous", update.get("previous_event_sha256"), source.get("event_sha256")),
        ("seq71 previous", completion.get("previous_event_sha256"), update.get("event_sha256")),
        ("seq71 update", completion.get("canonical_update_event_sha256"), update.get("event_sha256")),
        ("seq70 focus", update.get("focus_goal_id"), FP022_GOAL_ID),
        ("seq70 producer", update.get("produced_by_goal_id"), FP022_GOAL_ID),
        ("seq71 subject", completion.get("subject_goal_id"), FP022_GOAL_ID),
        ("seq71 focus", completion.get("focus_goal_id"), FP022_PARENT_GOAL_ID),
        ("seq71 focus content", completion.get("focus_goal_content_sha256"), FP022_PARENT_GOAL_SHA256),
        ("seq71 status", completion.get("status_changes"), {FP022_GOAL_ID: "COMPLETE_AT_TARGET"}),
        ("seq70 changed roles", update.get("changed_binding_roles"), ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP", FP022_COMPLETION_ROLE]),
        ("seq70 producer roles", update.get("produced_binding_roles"), ["IMPLEMENTATION_BACKLOG", "IMPLEMENTATION_GAP"]),
        (
            "seq70 changed subjects",
            update.get("changed_subject_ids_by_role"),
            {
                "IMPLEMENTATION_BACKLOG": ["FP-022"],
                "IMPLEMENTATION_GAP": ["FP-022", "GAP-031"],
            },
        ),
        (
            "seq70 producer subjects",
            update.get("producer_output_subject_ids_by_role"),
            {
                "IMPLEMENTATION_BACKLOG": ["FP-022"],
                "IMPLEMENTATION_GAP": ["FP-022", "GAP-031"],
            },
        ),
        ("seq71 evidence", completion.get("evidence_refs"), [FP022_COMPLETION_ROLE]),
    ):
        _require_equal(errors, f"FP022 completion {label}", actual, expected)
    if update.get("event_sha256") != event_sha256(update):
        errors.append("FP022 completion seq70 event seal differs")
    if completion.get("event_sha256") != event_sha256(completion):
        errors.append("FP022 completion seq71 event seal differs")
    for label, binding in (
        ("seq70 producer completion", update.get("producer_completion_receipt_binding")),
        ("seq71 completion", completion.get("completion_receipt_binding")),
    ):
        if not isinstance(binding, dict) or any(
            binding.get(key) != value for key, value in expected_binding.items()
        ):
            errors.append(f"FP022 completion {label} binding differs")
    review_binding = update.get("transition_control_review_binding")
    try:
        expected_review_paths = _fp022_completion_review_paths()
    except (ImportError, RuntimeError, TypeError, ValueError) as exc:
        errors.append(f"FP022 completion transition review authority differs: {exc}")
        expected_review_paths = {}
    if not isinstance(review_binding, dict) or set(review_binding) != set(expected_review_paths):
        errors.append("FP022 completion transition review binding differs")
    else:
        for role, relative in expected_review_paths.items():
            row = review_binding.get(role)
            path = resolve_repo_file(root, relative)
            if (
                not isinstance(row, dict)
                or row.get("path") != relative
                or path is None
                or row.get("sha256") != sha256_file(path)
                or row.get("byte_length") != path.stat().st_size
            ):
                errors.append(f"FP022 completion transition review differs: {role}")
    if len(history) == 71:
        runtime = completion.get("runtime_after")
        if (
            state.get("status_by_goal", {}).get(FP022_GOAL_ID) != "COMPLETE_AT_TARGET"
            or state.get("focus_goal_id") != FP022_PARENT_GOAL_ID
            or state.get("focus_goal_path") != FP022_PARENT_GOAL_PATH
            or state.get("focus_work_item_id") != ""
            or state.get("focus_source") != "WORKSTREAM_GRAPH"
            or state.get("ready_frontier_goal_ids")
            != [FP022_PARENT_GOAL_ID, "WS-GOAL-EPIC-12"]
            or not isinstance(runtime, dict)
            or runtime.get("focus_goal_id") != FP022_PARENT_GOAL_ID
        ):
            errors.append("FP022 completion final parent/frontier projection differs")
        if state.get("completion_evidence_by_goal", {}).get(FP022_GOAL_ID) != [FP022_COMPLETION_ROLE]:
            errors.append("FP022 completion evidence role differs")
        roles = canonical_binding_snapshot(checkpoint)
        completion_binding = roles.get(FP022_COMPLETION_ROLE)
        if not isinstance(completion_binding, dict) or any(
            completion_binding.get(key) != value for key, value in expected_binding.items()
        ):
            errors.append("FP022 completion canonical receipt binding differs")
        for role, relative in (
            ("IMPLEMENTATION_GAP", FP022_R028_GAP_PATH),
            ("IMPLEMENTATION_BACKLOG", FP022_R028_BACKLOG_PATH),
        ):
            binding = roles.get(role)
            path = resolve_repo_file(root, relative)
            if (
                not isinstance(binding, dict)
                or binding.get("path") != relative
                or path is None
                or binding.get("file_sha256") != sha256_file(path)
            ):
                errors.append(f"FP022 completion R028 canonical binding differs: {role}")
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
    errors.extend(validate_fp022_completion_seq70_71(root, checkpoint))
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
