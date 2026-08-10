from contextlib import contextmanager
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from unittest import mock

from scripts import check_walksafe_goal_graph_v2_4 as successor_graph


ROOT = Path(__file__).resolve().parents[1]
FROZEN_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph.py")
FROZEN_TEST_RELATIVE = Path("tests/test_walksafe_goal_graph.py")
HISTORY_CHECKPOINT_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-3/"
    "superseded-v2.2.0-active-checkpoint.json"
)
EXPECTED_FROZEN_CHECKER_SHA256 = (
    "64c5e733499029f608dc56d76096bb25a97bfdf043fa483180565a55d7d68d9e"
)
EXPECTED_FROZEN_TEST_SHA256 = (
    "ba195e5c26d8a91eca6b88e09587d2f58fcc213590977d7ef95b434e781db297"
)
HISTORICAL_CANONICAL_PREIMAGES = {
    "docs/deliverables/00-control/artifact-change-log.json": (
        "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c"
    ),
    "docs/deliverables/00-control/artifact-register.json": (
        "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6"
    ),
    "docs/deliverables/04-design/design-traceability-register.json": (
        "1ffb5861887d8edb26efbf0019cd4547baa6a24d8a73a576c3a5e9adf1e892aa"
    ),
    "docs/deliverables/05-implementation/module-register.json": (
        "54f2119ccb8598f06261590f8855c8d4c442cd662c4502501b62164a2cc0fe49"
    ),
    "docs/deliverables/06-testing/registers/test-cases.json": (
        "19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee"
    ),
}
SOURCE_PREIMAGE_MANIFEST_RELATIVE = Path(
    "docs/control/history/source-preimages/"
    "source-preimage-manifest-20260802-r001.json"
)
EXPECTED_SOURCE_PREIMAGE_MANIFEST_CONTENT_SHA256 = (
    "220bdce7135ecf19148af6c312771131a261cd1a1655f1c02e0b19f8ab32515f"
)
HISTORICAL_SOURCE_PREIMAGES = {
    "tests/test_walksafe_epic01_phase_b_trace_20260722.py": (
        8_988,
        "5e594cc4c752c24473cfea167f266ec714bb6470e8b5ce087f8d1712d0e3a5f4",
    ),
    "tests/test_walksafe_epic01_phase_c_trace_20260722.py": (
        19_334,
        "20cb319c3b3ebf3ab8fd1003494f64b0cc10d4ddf141b3d60ee3b97d9893339d",
    ),
}


def _preimage_path(digest: str) -> Path:
    return (
        ROOT
        / "docs/control/history/canonical-preimages/sha256"
        / f"{digest}.json"
    )


def _source_preimage_entries() -> dict[str, dict]:
    manifest = json.loads(
        (ROOT / SOURCE_PREIMAGE_MANIFEST_RELATIVE).read_text(encoding="utf-8")
    )
    claimed = manifest.pop("manifest_content_sha256", None)
    encoded = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    calculated = hashlib.sha256(encoded).hexdigest()
    if claimed != EXPECTED_SOURCE_PREIMAGE_MANIFEST_CONTENT_SHA256:
        raise RuntimeError("historical source preimage manifest seal differs")
    if calculated != claimed:
        raise RuntimeError("historical source preimage manifest seal is invalid")
    if manifest.get("schema_version") != "walksafe.source-preimage-manifest.v1":
        raise RuntimeError("historical source preimage manifest schema differs")
    metadata = manifest.get("metadata", {})
    if metadata.get("status") != "ACTIVE_HISTORICAL_REPLAY_ONLY":
        raise RuntimeError("historical source preimage manifest is not replay-only")
    expected_boundary = {
        "may_replace_live_successor_files": False,
        "may_change_current_artifact_state": False,
        "may_claim_current_feature_progress": False,
        "legacy_submission_candidate": False,
    }
    if manifest.get("authority_boundary") != expected_boundary:
        raise RuntimeError("historical source preimage authority boundary differs")

    entries = {
        item["logical_path"]: item
        for item in manifest.get("entries", [])
        if isinstance(item, dict) and isinstance(item.get("logical_path"), str)
    }
    if len(entries) != len(manifest.get("entries", [])):
        raise RuntimeError("historical source preimage entries are invalid")
    expected_entries = {
        logical_path: {
            "historical_bytes": size,
            "historical_sha256": digest,
            "blob_path": (
                "docs/control/history/source-preimages/sha256/"
                f"{digest}.py"
            ),
            "intended_use": "HISTORICAL_REPLAY_ONLY",
        }
        for logical_path, (size, digest) in HISTORICAL_SOURCE_PREIMAGES.items()
    }
    actual_entries = {
        logical_path: {
            key: item.get(key)
            for key in (
                "historical_bytes",
                "historical_sha256",
                "blob_path",
                "intended_use",
            )
        }
        for logical_path, item in entries.items()
    }
    if actual_entries != expected_entries:
        raise RuntimeError("historical source preimage entries differ")
    return entries


def _source_preimage_path(relative: str) -> Path:
    entry = _source_preimage_entries()[relative]
    preimage = ROOT / entry["blob_path"]
    if preimage.stat().st_size != entry["historical_bytes"]:
        raise RuntimeError(f"historical source preimage size differs: {relative}")
    if hashlib.sha256(preimage.read_bytes()).hexdigest() != entry["historical_sha256"]:
        raise RuntimeError(f"historical source preimage hash differs: {relative}")
    return preimage


def _historical_preimage(relative: str) -> Path | None:
    digest = HISTORICAL_CANONICAL_PREIMAGES.get(relative)
    if digest is not None:
        preimage = _preimage_path(digest)
        if hashlib.sha256(preimage.read_bytes()).hexdigest() != digest:
            return None
        return preimage
    if relative in HISTORICAL_SOURCE_PREIMAGES:
        return _source_preimage_path(relative)
    return None


def _load_history_test_case():
    spec = importlib.util.spec_from_file_location(
        "_walksafe_goal_graph_v2_2_frozen_tests",
        ROOT / FROZEN_TEST_RELATIVE,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("frozen v2.2 Goal graph test module cannot be loaded")
    frozen_tests = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(frozen_tests)
    base_case = frozen_tests.WalkSafeGoalGraphTest
    graph = frozen_tests.graph

    class WalkSafeGoalGraphV22HistoryTest(base_case):
        def setUp(self) -> None:
            super().setUp()
            self._live_checkpoint_relative = graph.CHECKPOINT_RELATIVE
            graph.CHECKPOINT_RELATIVE = HISTORY_CHECKPOINT_RELATIVE
            live_resolver = graph.resolve_safe_repo_file

            def historical_canonical_resolver(root, relative):
                relative_text = (
                    relative.as_posix()
                    if isinstance(relative, Path)
                    else relative
                )
                if (
                    Path(root).resolve() == ROOT.resolve()
                    and isinstance(relative_text, str)
                ):
                    preimage = _historical_preimage(relative_text)
                    if preimage is not None:
                        return preimage
                return live_resolver(root, relative)

            resolver_patch = mock.patch.object(
                graph,
                "resolve_safe_repo_file",
                historical_canonical_resolver,
            )
            resolver_patch.start()
            self.addCleanup(resolver_patch.stop)
            legacy_resolver = graph.legacy.resolve_safe_repo_file

            def historical_legacy_resolver(root, relative):
                relative_text = (
                    relative.as_posix()
                    if isinstance(relative, Path)
                    else relative
                )
                if (
                    Path(root).resolve() == ROOT.resolve()
                    and isinstance(relative_text, str)
                ):
                    preimage = _historical_preimage(relative_text)
                    if preimage is not None:
                        return preimage
                return legacy_resolver(root, relative)

            legacy_resolver_patch = mock.patch.object(
                graph.legacy,
                "resolve_safe_repo_file",
                historical_legacy_resolver,
            )
            legacy_resolver_patch.start()
            self.addCleanup(legacy_resolver_patch.stop)

        @contextmanager
        def fixture_root(self):
            with super().fixture_root() as root:
                for relative, digest in HISTORICAL_CANONICAL_PREIMAGES.items():
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(_preimage_path(digest), target)
                for relative in HISTORICAL_SOURCE_PREIMAGES:
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(_source_preimage_path(relative), target)
                yield root

        def tearDown(self) -> None:
            try:
                super().tearDown()
            finally:
                graph.CHECKPOINT_RELATIVE = self._live_checkpoint_relative

        def test_frozen_checker_and_test_hashes_are_exact(self) -> None:
            self.assertEqual(
                hashlib.sha256(
                    (ROOT / FROZEN_CHECKER_RELATIVE).read_bytes()
                ).hexdigest(),
                EXPECTED_FROZEN_CHECKER_SHA256,
            )
            self.assertEqual(
                hashlib.sha256(
                    (ROOT / FROZEN_TEST_RELATIVE).read_bytes()
                ).hexdigest(),
                EXPECTED_FROZEN_TEST_SHA256,
            )

        def test_historical_source_preimages_are_exact(self) -> None:
            entries = _source_preimage_entries()
            self.assertEqual(set(entries), set(HISTORICAL_SOURCE_PREIMAGES))
            for relative, (expected_size, expected_hash) in (
                HISTORICAL_SOURCE_PREIMAGES.items()
            ):
                preimage = _source_preimage_path(relative)
                self.assertEqual(preimage.stat().st_size, expected_size)
                self.assertEqual(
                    hashlib.sha256(preimage.read_bytes()).hexdigest(),
                    expected_hash,
                )

        def test_current_goal_graph_is_valid(self) -> None:
            self.assertEqual(
                successor_graph.validate(
                    ROOT,
                    check_continuation=False,
                ),
                [],
            )

        def test_canonical_next_action_wins_without_serializing_other_branches(
            self,
        ) -> None:
            checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
            backlog = self.load_binding_json(
                checkpoint,
                "IMPLEMENTATION_BACKLOG",
            )
            state = checkpoint["goal_execution"]
            calculated = graph.ready_frontier(
                nodes,
                state["status_by_goal"],
                state["materialized_child_goal_ids_by_parent"],
                state["blockers_by_goal"],
                backlog,
            )
            tail = state["transition_history"][-1]

            self.assertEqual(calculated, state["ready_frontier_goal_ids"])
            self.assertEqual(
                tail["runtime_after"]["ready_frontier_goal_ids"],
                calculated,
            )
            self.assertEqual(state["focus_goal_id"], calculated[0])
            self.assertEqual(
                state["focus_goal_id"],
                "WS-GOAL-EPIC-02-FP-005-R001",
            )
            self.assertEqual(tail["focus_goal_id"], state["focus_goal_id"])

        def test_policy_gap_work_item_binds_exactly_one_pair(self) -> None:
            checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
            backlog = self.load_binding_json(
                checkpoint,
                "IMPLEMENTATION_BACKLOG",
            )
            state = checkpoint["goal_execution"]
            node = nodes[state["focus_goal_id"]]
            next_action = backlog["next_single_action"]

            self.assertEqual(
                node["work_item_id"],
                next_action["work_item_id"],
            )
            self.assertEqual(
                node["source_policy_ids"],
                [next_action["source_policy_id"]],
            )
            self.assertEqual(node["gap_ids"], [next_action["gap_id"]])
            self.assertEqual(
                (
                    next_action["source_policy_id"],
                    next_action["gap_id"],
                ),
                ("FP-005", "GAP-014"),
            )

        def test_sequence_14_preserves_fp004_gap013_history(self) -> None:
            checkpoint = graph.load_json(ROOT / graph.CHECKPOINT_RELATIVE)
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(ROOT, manifest, include_dynamic=True)
            history_by_sequence = {
                event["sequence"]: event
                for event in checkpoint["goal_execution"][
                    "transition_history"
                ]
            }
            event = history_by_sequence[14]
            goal_id = "WS-GOAL-EPIC-02-FP-004-R001"
            node = nodes[goal_id]

            self.assertEqual(event["event_type"], "GOAL_READY")
            self.assertEqual(event["subject_goal_id"], goal_id)
            self.assertEqual(event["focus_goal_id"], goal_id)
            self.assertEqual(
                event["runtime_after"]["ready_frontier_goal_ids"][0],
                goal_id,
            )
            self.assertEqual(node["source_policy_ids"], ["FP-004"])
            self.assertEqual(node["gap_ids"], ["GAP-013"])

    return WalkSafeGoalGraphV22HistoryTest


WalkSafeGoalGraphV22HistoryTest = _load_history_test_case()
