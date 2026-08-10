from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
from types import ModuleType
import unittest
from unittest import mock

from scripts import check_walksafe_goal_graph_v2_4 as active_successor_graph
from scripts import check_walksafe_goal_graph_v2_3 as successor_graph


ROOT = Path(__file__).resolve().parents[1]
ARCHIVED_CHECKPOINT_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-3/"
    "superseded-v2.2.0-active-checkpoint.json"
)
ARCHIVED_CHECKPOINT = ROOT / ARCHIVED_CHECKPOINT_RELATIVE
SUCCESSOR_ARCHIVED_CHECKPOINT_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "superseded-v2.3.0-active-checkpoint.json"
)
SUCCESSOR_ARCHIVED_CHECKPOINT = ROOT / SUCCESSOR_ARCHIVED_CHECKPOINT_RELATIVE
EXPECTED_SUCCESSOR_ARCHIVED_CHECKPOINT_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)

EXPECTED_FROZEN_SOURCE_SHA256 = {
    "tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py": (
        "1e75539c29cf160580587d8dd91ff86392d98373f091cf184189212c3d53cc0f"
    ),
    "tests/test_walksafe_npc_permission_session_trace_20260724.py": (
        "7887c14e2128f19097ccc93fc2793359ae1ef8b3e437f94c9102cacba615fde8"
    ),
    "tests/test_walksafe_fp004_priority_user_trace_20260724.py": (
        "aa53cd274681b2b5f2c82171c0c0e906307b12d6120cd40d38a787fdb8d7b154"
    ),
    "scripts/build_walksafe_fp018_walk_state_recovery_trace_20260724.py": (
        "33e3578e23f76418165ec1af6b65e6b141c2d726ed40a696bca6b5743c446be6"
    ),
    "scripts/build_walksafe_npc_permission_session_trace_20260724.py": (
        "4ef0e274b56802e258be720423ac47024101d4b80a19d44c4045e3275497efed"
    ),
    "scripts/build_walksafe_fp004_priority_user_trace_20260724.py": (
        "96ce2b07bd42e4b40fa39e467281e6856d821fc0d4103c9a31d21f09d7bb6c1c"
    ),
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_frozen_sources() -> None:
    for relative, expected in EXPECTED_FROZEN_SOURCE_SHA256.items():
        path = ROOT / relative
        if not path.is_file():
            raise RuntimeError(f"frozen source is missing: {relative}")
        actual = sha256_file(path)
        if actual != expected:
            raise RuntimeError(
                f"frozen source changed: {relative}: expected {expected}, got {actual}"
            )


def load_frozen_test_module(module_name: str, relative: str) -> ModuleType:
    path = ROOT / relative
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load frozen test module: {relative}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


require_frozen_sources()
if not ARCHIVED_CHECKPOINT.is_file():
    raise RuntimeError(
        f"archived v2.2 checkpoint is missing: {ARCHIVED_CHECKPOINT_RELATIVE}"
    )
if (
    not SUCCESSOR_ARCHIVED_CHECKPOINT.is_file()
    or sha256_file(SUCCESSOR_ARCHIVED_CHECKPOINT)
    != EXPECTED_SUCCESSOR_ARCHIVED_CHECKPOINT_SHA256
):
    raise RuntimeError("archived v2.3 successor checkpoint differs")

_FP018_MODULE = load_frozen_test_module(
    "_walksafe_fp018_trace_v2_2_frozen",
    "tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py",
)
_NPC_MODULE = load_frozen_test_module(
    "_walksafe_npc_permission_session_trace_v2_2_frozen",
    "tests/test_walksafe_npc_permission_session_trace_20260724.py",
)
_FP004_MODULE = load_frozen_test_module(
    "_walksafe_fp004_priority_user_trace_v2_2_frozen",
    "tests/test_walksafe_fp004_priority_user_trace_20260724.py",
)


def completed_cross_package_successor_reaches_live(
    goal_id: str,
    relative_path: str,
    historical_sha256: str,
) -> bool:
    checkpoint = successor_graph.load_json(
        SUCCESSOR_ARCHIVED_CHECKPOINT
    )
    if successor_graph.completed_cross_package_successor_reaches_live(
        ROOT,
        checkpoint=checkpoint,
        goal_id=goal_id,
        relative_path=relative_path,
        historical_sha256=historical_sha256,
    ):
        return True
    active_checkpoint = active_successor_graph.load_json(
        ROOT / active_successor_graph.CHECKPOINT_RELATIVE
    )
    return active_successor_graph.fp011_successor_artifact_reaches_live(
        ROOT,
        checkpoint=active_checkpoint,
        archive=checkpoint,
        goal_id=goal_id,
        relative_path=relative_path,
        historical_sha256=historical_sha256,
    )


_FP018_MODULE.completed_successor_reaches_live = (
    completed_cross_package_successor_reaches_live
)
_NPC_MODULE.completed_successor_reaches_live = (
    completed_cross_package_successor_reaches_live
)

_FP018_BASE = _FP018_MODULE.WalkSafeFp018TraceTest
_NPC_BASE = _NPC_MODULE.WalkSafeNpcPermissionSessionTraceTest
_FP004_BASE = _FP004_MODULE.WalkSafeFp004PriorityUserTraceTest

EXPECTED_ORIGINAL_TEST_COUNTS = {
    _FP018_BASE: 6,
    _NPC_BASE: 7,
    _FP004_BASE: 10,
}
for _case, _expected_count in EXPECTED_ORIGINAL_TEST_COUNTS.items():
    _actual_count = len(unittest.defaultTestLoader.getTestCaseNames(_case))
    if _actual_count != _expected_count:
        raise RuntimeError(
            f"frozen test count changed for {_case.__name__}: "
            f"expected {_expected_count}, got {_actual_count}"
        )
if sum(EXPECTED_ORIGINAL_TEST_COUNTS.values()) != 23:
    raise RuntimeError("historical EPIC-02 trace adapter must reuse exactly 23 tests")


class _ArchivedCheckpointMixin:
    frozen_builder = None
    frozen_graph = None

    def setUp(self) -> None:
        builder_patch = mock.patch.object(
            self.frozen_builder,
            "CHECKPOINT",
            ARCHIVED_CHECKPOINT,
        )
        graph_patch = mock.patch.object(
            self.frozen_graph,
            "CHECKPOINT_RELATIVE",
            ARCHIVED_CHECKPOINT_RELATIVE,
        )
        builder_patch.start()
        self.addCleanup(builder_patch.stop)
        graph_patch.start()
        self.addCleanup(graph_patch.stop)
        super().setUp()


class TestWalkSafeFp018TraceV22History(_ArchivedCheckpointMixin, _FP018_BASE):
    frozen_builder = _FP018_MODULE.builder
    frozen_graph = _FP018_MODULE.graph

    def test_builder_outputs_are_current(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            return_code = self.frozen_builder.main([])
        self.assertEqual(return_code, 0, stderr.getvalue())
        self.assertIn("FP-018 trace: PASS outputs=11", stdout.getvalue())
        self.assertIn("mode=SEALED_COMPLETION", stdout.getvalue())


class TestWalkSafeNpcPermissionSessionTraceV22History(
    _ArchivedCheckpointMixin,
    _NPC_BASE,
):
    frozen_builder = _NPC_MODULE.builder
    frozen_graph = _NPC_MODULE.graph

    def test_builder_outputs_are_current(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            return_code = self.frozen_builder.main([])
        self.assertEqual(return_code, 0, stderr.getvalue())
        self.assertIn(
            "NPC permission/session trace: PASS outputs=12",
            stdout.getvalue(),
        )
        self.assertIn("mode=SEALED_COMPLETION", stdout.getvalue())


class TestWalkSafeFp004PriorityUserTraceV22History(
    _ArchivedCheckpointMixin,
    _FP004_BASE,
):
    frozen_builder = _FP004_MODULE.builder
    frozen_graph = _FP004_MODULE.graph


del _case
del _expected_count
del _actual_count
del _FP018_BASE
del _NPC_BASE
del _FP004_BASE
