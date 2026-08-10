"""Replay the byte-frozen v2.3 Goal control suite against its archive."""

from __future__ import annotations

from contextlib import contextmanager
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from unittest import mock

from scripts import check_walksafe_goal_graph_v2_4 as successor_graph


ROOT = Path(__file__).resolve().parents[1]
HISTORY_CHECKPOINT_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "superseded-v2.3.0-active-checkpoint.json"
)
EXPECTED_ARCHIVED_CHECKPOINT_RAW_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)
EXPECTED_V23_MANIFEST_SHA256 = (
    "dfa615686b0223826497fae424c1f3c41538b271102879a9497a33b81f66329c"
)
EXPECTED_V23_EVENT_COUNT = 17
EXPECTED_V23_TAIL_SHA256 = (
    "bc71126a8a0b71a97ce4cc89a86e0ce1d89739453f719f8820dc882c74a101d6"
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


def _verified_source_preimages() -> dict[str, Path]:
    manifest = json.loads(
        (ROOT / SOURCE_PREIMAGE_MANIFEST_RELATIVE).read_text(encoding="utf-8")
    )
    claimed = manifest.pop("manifest_content_sha256")
    canonical = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    if (
        claimed != EXPECTED_SOURCE_PREIMAGE_MANIFEST_CONTENT_SHA256
        or hashlib.sha256(canonical).hexdigest() != claimed
    ):
        raise AssertionError("source preimage manifest seal is invalid")
    if (
        manifest.get("schema_version") != "walksafe.source-preimage-manifest.v1"
        or manifest.get("metadata", {}).get("status")
        != "ACTIVE_HISTORICAL_REPLAY_ONLY"
        or any(manifest.get("authority_boundary", {}).values())
    ):
        raise AssertionError("source preimage manifest is not replay-only")

    entries = {
        item["logical_path"]: item
        for item in manifest.get("entries", [])
    }
    if len(entries) != len(manifest.get("entries", [])):
        raise AssertionError("source preimage manifest has duplicate paths")
    if set(entries) != set(HISTORICAL_SOURCE_PREIMAGES):
        raise AssertionError("source preimage manifest path set differs")

    verified: dict[str, Path] = {}
    for relative, (expected_size, expected_digest) in (
        HISTORICAL_SOURCE_PREIMAGES.items()
    ):
        entry = entries[relative]
        expected_blob = (
            "docs/control/history/source-preimages/sha256/"
            f"{expected_digest}.py"
        )
        if (
            entry.get("historical_bytes") != expected_size
            or entry.get("historical_sha256") != expected_digest
            or entry.get("blob_path") != expected_blob
            or entry.get("intended_use") != "HISTORICAL_REPLAY_ONLY"
        ):
            raise AssertionError(f"source preimage entry differs: {relative}")
        blob = ROOT / expected_blob
        if blob.stat().st_size != expected_size:
            raise AssertionError(f"source preimage size differs: {relative}")
        if _sha256_file(blob) != expected_digest:
            raise AssertionError(f"source preimage hash differs: {relative}")
        verified[relative] = blob
    return verified


def _historical_preimage(
    relative: str,
    source_preimages: dict[str, Path],
) -> Path | None:
    digest = HISTORICAL_CANONICAL_PREIMAGES.get(relative)
    if digest is not None:
        preimage = _preimage_path(digest)
        return preimage if _sha256_file(preimage) == digest else None
    return source_preimages.get(relative)


EXPECTED_FROZEN_SOURCE_SHA256 = {
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


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_history_test_case():
    frozen_test_path = ROOT / "tests/test_walksafe_goal_graph_v2_3.py"
    spec = importlib.util.spec_from_file_location(
        "_walksafe_goal_graph_v2_3_frozen_tests",
        frozen_test_path,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("frozen v2.3 Goal graph test module cannot be loaded")
    frozen_tests = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(frozen_tests)
    base_case = frozen_tests.WalkSafeGoalGraphV23Test
    graph = frozen_tests.graph

    class WalkSafeGoalGraphV23HistoryTest(base_case):
        """Run v2.3 semantics only against the byte-exact v2.3 archive."""

        def setUp(self) -> None:
            source_preimages = _verified_source_preimages()
            self._live_checkpoint_relative = graph.CHECKPOINT_RELATIVE
            graph.CHECKPOINT_RELATIVE = HISTORY_CHECKPOINT_RELATIVE
            live_resolver = graph.resolve_safe_repo_file

            def historical_canonical_resolver(root, relative):
                if (
                    Path(root).resolve() == ROOT.resolve()
                    and isinstance(relative, str)
                ):
                    preimage = _historical_preimage(relative, source_preimages)
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
                if (
                    Path(root).resolve() == ROOT.resolve()
                    and isinstance(relative, str)
                ):
                    preimage = _historical_preimage(relative, source_preimages)
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
            frozen_validate = graph.validate

            def successor_aware_validate(*args, **kwargs):
                validation_root = (
                    Path(args[0])
                    if args
                    else Path(kwargs.get("root", ROOT))
                )
                return successor_graph.filter_frozen_v23_successor_errors(
                    validation_root,
                    frozen_validate(*args, **kwargs),
                )

            validate_patch = mock.patch.object(
                graph,
                "validate",
                successor_aware_validate,
            )
            validate_patch.start()
            self.addCleanup(validate_patch.stop)
            super().setUp()

        @contextmanager
        def fixture_root(self):
            with super().fixture_root() as root:
                for relative, digest in HISTORICAL_CANONICAL_PREIMAGES.items():
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(_preimage_path(digest), target)
                for relative, source in _verified_source_preimages().items():
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
                yield root

        def tearDown(self) -> None:
            try:
                super().tearDown()
            finally:
                graph.CHECKPOINT_RELATIVE = self._live_checkpoint_relative

        def load_current_v24_successor_context(
            self,
        ) -> tuple[dict, dict]:
            checkpoint = successor_graph._load_exact_json(
                ROOT,
                successor_graph.CHECKPOINT_RELATIVE.as_posix(),
            )
            archive = successor_graph._load_exact_json(
                ROOT,
                successor_graph.V23_ARCHIVE_RELATIVE.as_posix(),
            )
            self.assertIsInstance(checkpoint, dict)
            self.assertIsInstance(archive, dict)
            return checkpoint, archive

        def stage_fp005_completion_witness(
            self,
            root: Path,
            checkpoint: dict,
            *,
            append_event: bool = True,
        ) -> tuple[dict, dict]:
            self.assertNotEqual(root.resolve(), ROOT.resolve())
            event, binding = super().stage_fp005_completion_witness(
                root,
                checkpoint,
                append_event=append_event,
            )
            deferred_paths = {
                self.archived_changed_artifact_path(root, error)
                for error in self.archived_changed_artifact_errors(root)
            }
            receipt_path = root / binding["path"]
            receipt = graph.load_json(receipt_path)
            implementation_item = next(
                item
                for item in receipt["result_evidence"]
                if item["kind"] == "IMPLEMENTATION_RECORD"
            )
            implementation_path = root / implementation_item["path"]
            implementation = graph.load_json(implementation_path)
            candidates = [
                item
                for item in implementation["changed_artifacts"]
                if (
                    item.get("before_sha256") is not None
                    and item["path"] in deferred_paths
                    and item.get("after_sha256")
                    != item["before_sha256"]
                    and graph.sha256_file(root / item["path"])
                    != item["before_sha256"]
                )
            ]
            changed = min(candidates, key=lambda item: item["path"])
            changed["after_sha256"] = graph.sha256_file(
                root / changed["path"]
            )
            if "implementation_content_set_sha256" in implementation:
                implementation["implementation_content_set_sha256"] = (
                    successor_graph._implementation_content_set_sha256(
                        implementation["changed_artifacts"]
                    )
                )
            self.write_json(implementation_path, implementation)
            implementation_item["sha256"] = graph.sha256_file(
                implementation_path
            )
            self.write_json(receipt_path, receipt)
            binding["file_sha256"] = graph.sha256_file(receipt_path)
            role = next(iter(event["completion_evidence_bindings"]))
            event["completion_evidence_bindings"][role] = self._clone(
                binding
            )
            event["completion_receipt_binding"] = self._clone(binding)
            event["event_sha256"] = graph.event_sha256(event)
            return event, binding

        def test_staged_fp005_completion_preserves_newer_live_bytes(
            self,
        ) -> None:
            current_checkpoint, current_archive = (
                self.load_current_v24_successor_context()
            )
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                deferred_errors = set(
                    self.archived_changed_artifact_errors(root)
                )
                live_paths = {
                    self.archived_changed_artifact_path(root, error)
                    for error in deferred_errors
                }
                live_sha256_before = {
                    relative: graph.sha256_file(root / relative)
                    for relative in live_paths
                }
                self.stage_fp005_completion_witness(root, checkpoint)

                staged_errors = set(
                    self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )
                )
                live_sha256_after = {
                    relative: graph.sha256_file(root / relative)
                    for relative in live_paths
                }

            filtered_errors = set(
                successor_graph.filter_frozen_v23_successor_errors(
                    ROOT,
                    sorted(staged_errors),
                    checkpoint=current_checkpoint,
                    archive=current_archive,
                )
            )
            self.assertEqual(live_sha256_before, live_sha256_after)
            self.assertTrue(staged_errors)
            self.assertLessEqual(staged_errors, deferred_errors)
            self.assertLess(filtered_errors, staged_errors)

        def test_staged_fp005_completion_rejects_broken_before_hash(
            self,
        ) -> None:
            current_checkpoint, current_archive = (
                self.load_current_v24_successor_context()
            )
            with self.fixture_root() as root:
                checkpoint = graph.load_json(
                    root / graph.CHECKPOINT_RELATIVE
                )
                event, binding = self.stage_fp005_completion_witness(
                    root,
                    checkpoint,
                )
                valid_errors = set(
                    self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )
                )
                deferred_paths = {
                    self.archived_changed_artifact_path(
                        root,
                        error,
                    )
                    for error in self.archived_changed_artifact_errors(
                        root
                    )
                }
                receipt_path = root / binding["path"]
                receipt = graph.load_json(receipt_path)
                implementation_item = next(
                    item
                    for item in receipt["result_evidence"]
                    if item["kind"] == "IMPLEMENTATION_RECORD"
                )
                implementation_path = (
                    root / implementation_item["path"]
                )
                implementation = graph.load_json(
                    implementation_path
                )
                changed = next(
                    item
                    for item in implementation["changed_artifacts"]
                    if (
                        item.get("before_sha256") is not None
                        and item["path"] in deferred_paths
                        and graph.sha256_file(root / item["path"])
                        == item["after_sha256"]
                    )
                )
                changed["before_sha256"] = "f" * 64
                self.write_json(implementation_path, implementation)
                implementation_item["sha256"] = graph.sha256_file(
                    implementation_path
                )
                self.write_json(receipt_path, receipt)
                binding["file_sha256"] = graph.sha256_file(receipt_path)
                role = next(
                    iter(event["completion_evidence_bindings"])
                )
                event["completion_evidence_bindings"][role] = self._clone(
                    binding
                )
                event["completion_receipt_binding"] = self._clone(binding)
                event["event_sha256"] = graph.event_sha256(event)

                errors = set(
                    self.deferred_archive_replay_errors(
                        root,
                        checkpoint,
                    )
                )

            self.assertLess(valid_errors, errors)

        def test_frozen_v23_control_sources_are_byte_exact(self) -> None:
            for relative, expected in EXPECTED_FROZEN_SOURCE_SHA256.items():
                self.assertEqual(
                    _sha256_file(ROOT / relative),
                    expected,
                    relative,
                )

        def test_historical_trace_sources_use_sealed_preimages(self) -> None:
            verified = _verified_source_preimages()
            for relative, (expected_size, expected_digest) in (
                HISTORICAL_SOURCE_PREIMAGES.items()
            ):
                source = verified[relative]
                self.assertEqual(source.stat().st_size, expected_size)
                self.assertEqual(_sha256_file(source), expected_digest)
                self.assertEqual(
                    graph.resolve_safe_repo_file(ROOT, relative),
                    source,
                )
                self.assertEqual(
                    graph.legacy.resolve_safe_repo_file(ROOT, relative),
                    source,
                )
            with self.fixture_root() as root:
                for relative, (_, expected_digest) in (
                    HISTORICAL_SOURCE_PREIMAGES.items()
                ):
                    self.assertEqual(
                        _sha256_file(root / relative),
                        expected_digest,
                    )

        def test_archived_v23_checkpoint_identity_is_exact(self) -> None:
            archived_path = ROOT / HISTORY_CHECKPOINT_RELATIVE
            self.assertEqual(
                _sha256_file(archived_path),
                EXPECTED_ARCHIVED_CHECKPOINT_RAW_SHA256,
            )
            archived = graph.load_json(archived_path)
            state = archived["goal_execution"]
            self.assertEqual(
                state["package_id"],
                "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3",
            )
            self.assertEqual(state["static_plan_version"], "2.3.0")
            self.assertEqual(
                state["static_plan_manifest_sha256"],
                EXPECTED_V23_MANIFEST_SHA256,
            )
            self.assertEqual(
                len(state["transition_history"]),
                EXPECTED_V23_EVENT_COUNT,
            )
            self.assertEqual(
                state["transition_history_anchor_sha256"],
                EXPECTED_V23_TAIL_SHA256,
            )
            self.assertEqual(
                state["transition_history"][-1]["event_sha256"],
                EXPECTED_V23_TAIL_SHA256,
            )
            self.assertEqual(
                state["focus_goal_id"],
                "WS-GOAL-EPIC-02-FP-011-R001",
            )
            self.assertEqual(
                state["status_by_goal"]["WS-GOAL-EPIC-02-FP-011-R001"],
                "READY",
            )

        def test_current_v24_goal_graph_is_valid(self) -> None:
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
            """Replace the frozen test's stale FP010 literal at v2.3 seq17."""
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(
                ROOT,
                manifest,
                include_dynamic=True,
            )
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

            self.assertEqual(
                calculated,
                state["ready_frontier_goal_ids"],
            )
            self.assertEqual(
                calculated,
                [
                    "WS-GOAL-EPIC-02-FP-011-R001",
                    "WS-GOAL-EPIC-03",
                    "WS-GOAL-EPIC-12",
                ],
            )

        def test_policy_gap_work_item_binds_exactly_one_pair(
            self,
        ) -> None:
            """Replace the frozen test's stale FP010/GAP019 literals."""
            checkpoint = graph.load_json(
                ROOT / graph.CHECKPOINT_RELATIVE
            )
            manifest = graph.load_json(ROOT / graph.MANIFEST_RELATIVE)
            nodes = self.load_nodes(
                ROOT,
                manifest,
                include_dynamic=True,
            )
            node = nodes[checkpoint["goal_execution"]["focus_goal_id"]]

            self.assertEqual(node["source_policy_ids"], ["FP-011"])
            self.assertEqual(node["gap_ids"], ["GAP-020"])

    return WalkSafeGoalGraphV23HistoryTest


WalkSafeGoalGraphV23HistoryTest = _load_history_test_case()
