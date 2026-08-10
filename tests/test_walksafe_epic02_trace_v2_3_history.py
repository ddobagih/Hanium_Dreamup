"""Replay completed FP005/FP006/FP010 traces on the frozen v2.3 archive."""

from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path
import sys
from types import ModuleType
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
ARCHIVED_CHECKPOINT_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "superseded-v2.3.0-active-checkpoint.json"
)
ARCHIVED_CHECKPOINT = ROOT / ARCHIVED_CHECKPOINT_RELATIVE
EXPECTED_ARCHIVED_CHECKPOINT_RAW_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)
EXPECTED_FROZEN_SOURCE_SHA256 = {
    "tests/test_walksafe_fp005_official_environment_trace_20260724.py": (
        "3f2fd117f20b5d60ecea0e40d58cdcd90c0e9f7df8b655c3fcf9249c25341014"
    ),
    "tests/test_walksafe_fp006_phone_mounting_trace_20260724.py": (
        "ae53ce413fdf6bbd03270bbb7e2d79b1e5e5a86ba6be90eec1940cf42de292f1"
    ),
    "tests/test_walksafe_fp010_first_run_registration_trace_20260725.py": (
        "f1aad2bdfbc734d281aede5e6a1a3799c0da97dccc96390bbca65126649a0a5c"
    ),
    "scripts/build_walksafe_fp005_official_environment_trace_20260724.py": (
        "57faffe7a56e55d37a68c4114e42b8ad5c4d4e66fbd90bc149b44f09de03aa1b"
    ),
    "scripts/build_walksafe_fp006_phone_mounting_trace_20260724.py": (
        "5db4147da48d23adce7e9a10d9926fe740054cdfc52bb1c6f24b6b89725e95da"
    ),
    "scripts/build_walksafe_fp010_first_run_registration_trace_20260725.py": (
        "68a568b5c2a73a254a99997c5e8b80a118f3a0cd31d7f23c1328190aa0ba4a46"
    ),
}


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_frozen_sources() -> None:
    for relative, expected in EXPECTED_FROZEN_SOURCE_SHA256.items():
        path = ROOT / relative
        if not path.is_file():
            raise RuntimeError(f"frozen source is missing: {relative}")
        actual = _sha256_file(path)
        if actual != expected:
            raise RuntimeError(
                f"frozen source changed: {relative}: "
                f"expected {expected}, got {actual}"
            )
    if _sha256_file(ARCHIVED_CHECKPOINT) != (
        EXPECTED_ARCHIVED_CHECKPOINT_RAW_SHA256
    ):
        raise RuntimeError("archived v2.3 checkpoint bytes changed")


def _load_frozen_test_module(module_name: str, relative: str) -> ModuleType:
    path = ROOT / relative
    spec = importlib.util.spec_from_file_location(module_name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load frozen test module: {relative}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


_require_frozen_sources()
_FP005_MODULE = _load_frozen_test_module(
    "_walksafe_fp005_trace_v2_3_frozen",
    "tests/test_walksafe_fp005_official_environment_trace_20260724.py",
)
_FP006_MODULE = _load_frozen_test_module(
    "_walksafe_fp006_trace_v2_3_frozen",
    "tests/test_walksafe_fp006_phone_mounting_trace_20260724.py",
)
_FP010_MODULE = _load_frozen_test_module(
    "_walksafe_fp010_trace_v2_3_frozen",
    "tests/test_walksafe_fp010_first_run_registration_trace_20260725.py",
)

_FP005_BASE = _FP005_MODULE.WalkSafeFp005OfficialEnvironmentTraceTest
_FP006_BASE = _FP006_MODULE.WalkSafeFp006PhoneMountingTraceTest
_FP010_BASE = _FP010_MODULE.WalkSafeFp010FirstRunRegistrationTraceTest

EXPECTED_ORIGINAL_TEST_COUNTS = {
    _FP005_BASE: 10,
    _FP006_BASE: 10,
    _FP010_BASE: 10,
}
for _case, _expected_count in EXPECTED_ORIGINAL_TEST_COUNTS.items():
    _actual_count = len(unittest.defaultTestLoader.getTestCaseNames(_case))
    if _actual_count != _expected_count:
        raise RuntimeError(
            f"frozen test count changed for {_case.__name__}: "
            f"expected {_expected_count}, got {_actual_count}"
        )
if sum(EXPECTED_ORIGINAL_TEST_COUNTS.values()) != 30:
    raise RuntimeError("historical v2.3 EPIC-02 adapter must reuse 30 tests")


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


class TestWalkSafeFp005TraceV23History(
    _ArchivedCheckpointMixin,
    _FP005_BASE,
):
    frozen_builder = _FP005_MODULE.builder
    frozen_graph = _FP005_MODULE.graph


class TestWalkSafeFp006TraceV23History(
    _ArchivedCheckpointMixin,
    _FP006_BASE,
):
    frozen_builder = _FP006_MODULE.builder
    frozen_graph = _FP006_MODULE.graph


class TestWalkSafeFp010TraceV23History(
    _ArchivedCheckpointMixin,
    _FP010_BASE,
):
    frozen_builder = _FP010_MODULE.builder
    frozen_graph = _FP010_MODULE.graph


del _case
del _expected_count
del _actual_count
del _FP005_BASE
del _FP006_BASE
del _FP010_BASE
