"""Focused lifecycle and frozen-predecessor regressions for Goal v2.4."""

from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import materialize_walksafe_fp048_goal_20260802 as fp048_goal


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
V24_PACKAGE = ROOT / "docs/control/goals/walksafe-completion-graph-v2-4"
V24_MANIFEST = V24_PACKAGE / "static-plan-manifest-v2.4.0.json"
V23_ARCHIVE = V24_PACKAGE / "superseded-v2.3.0-active-checkpoint.json"
V23_SUPERSESSION_RECORD = (
    V24_PACKAGE / "active-supersession-record-v2.3.0.json"
)
GRADLE_VERIFICATION_METADATA_RELATIVE = (
    "apps/android/gradle/verification-metadata.xml"
)
GRADLE_VERIFICATION_METADATA_SEALED_SHA256 = (
    "0f2fc21ad52bd81b877f4a2cecdf587841f4dcac2c87e0e3139a0f94374c0084"
)
GRADLE_VERIFICATION_METADATA_CURRENT_SHA256 = (
    "eaa662a434257a71c595ab510889657579e5e5a107041813338a257b56674d17"
)
FP008_START_GATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
)
FP008_RESUME_GATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
)
FP046_START_GATE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005"
)
FP008_START_GATE_RECEIPT = ROOT / (
    "docs/control/execution/goal-gates/"
    f"{FP008_START_GATE_EVENT_ID}/"
    "implementation-start-gate-receipt.json"
)
FP008_RESUME_GATE_RECEIPT = ROOT / (
    "docs/control/execution/goal-gates/"
    f"{FP008_RESUME_GATE_EVENT_ID}/"
    "implementation-resume-gate-receipt.json"
)
FP046_START_GATE_RECEIPT = ROOT / (
    "docs/control/execution/goal-gates/"
    f"{FP046_START_GATE_EVENT_ID}/"
    "implementation-start-gate-receipt.json"
)

V24_PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
V24_PLAN_VERSION = "2.4.0"
FP011_GOAL_ID = "WS-GOAL-EPIC-02-FP-011-R001"
EXPECTED_V23_CHECKPOINT_RAW_SHA256 = (
    "7f62b09941e614f11d9c21c84e5f63df2ff070b00c569550faf2de7807de67b6"
)
EXPECTED_V23_MANIFEST_SHA256 = (
    "dfa615686b0223826497fae424c1f3c41538b271102879a9497a33b81f66329c"
)
EXPECTED_V23_EVENT_COUNT = 17
EXPECTED_V23_TAIL_SHA256 = (
    "bc71126a8a0b71a97ce4cc89a86e0ce1d89739453f719f8820dc882c74a101d6"
)
EXPECTED_V23_PACKAGE_PATH_COUNT = 26
EXPECTED_V23_PACKAGE_PATH_SET_SHA256 = (
    "74c04874df3766ca61cb7153eeecefe421a3d447ed3ddef2775e7538aa4a266b"
)
EXPECTED_V23_PACKAGE_CONTENT_SET_SHA256 = (
    "822189aade16518f68a4be68ce7086f0dcf54f4d83c85bf1c2bb08aa7a3458d0"
)
EXPECTED_V23_FROZEN_SOURCE_SHA256 = {
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


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def project_fp008_corrective_candidate(corrective) -> dict[str, object]:
    engine = corrective.engine
    checkpoint = ROOT / "docs/control/walksafe-project-continuation-checkpoint.json"
    source = json.loads(checkpoint.read_bytes())
    source_paths = source["working_tree_snapshot"]["managed_changed_paths"]
    candidate_paths = sorted(set(source_paths) | set(engine.ADDED_MANAGED_PATHS))
    digests = {
        relative: sha256_file(ROOT / relative)
        for relative in candidate_paths
    }
    reconstructed = {relative: digests[relative] for relative in source_paths}
    for relative, expected in engine.AUTHORIZED_EXISTING_DELTAS.items():
        if digests[relative] != expected["candidate_sha256"]:
            raise AssertionError(f"corrective candidate differs: {relative}")
        reconstructed[relative] = expected["source_sha256"]

    def content_set(paths: list[str], values: dict[str, str]) -> str:
        digest = hashlib.sha256()
        for relative in sorted(paths):
            digest.update(relative.encode("utf-8"))
            digest.update(b"\0")
            digest.update(values[relative].encode("ascii"))
            digest.update(b"\n")
        return digest.hexdigest()

    if content_set(source_paths, reconstructed) != engine.SOURCE_CONTENT_SET_SHA256:
        raise AssertionError("corrective source reconstruction differs")
    projected = copy.deepcopy(source)
    path_hash = hashlib.sha256(
        ("\n".join(candidate_paths) + "\n").encode("utf-8")
    ).hexdigest()
    content_hash = content_set(candidate_paths, digests)
    snapshot = projected["working_tree_snapshot"]
    snapshot.update(
        managed_changed_paths=candidate_paths,
        managed_changed_path_count=len(candidate_paths),
        path_set_sha256=path_hash,
        content_set_sha256=content_hash,
    )
    handoff = projected["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(candidate_paths)
    handoff["source_commit_or_snapshot"].update(
        file_count=len(candidate_paths),
        path_set_sha256=path_hash,
        content_set_sha256=content_hash,
    )
    return projected


def prepare_fp008_reconcile_with_independent_authority(reconcile):
    source = json.loads((ROOT / reconcile.CHECKPOINT).read_bytes())
    source_paths = reconcile._require_exact_seq47(source)
    paths = sorted(set(source_paths) | set(reconcile.ADDED_MANAGED_PATHS))
    cohort = reconcile.ReconcileCohort.capture(ROOT, paths)
    try:
        projected = reconcile._project_from_retained(ROOT, source, cohort)
        projected_bytes = reconcile.json_bytes(projected)
        document = reconcile.authority_document(
            ROOT,
            projected,
            projected_bytes,
            cohort,
            approval_id="ab" * 32,
            generation_nonce="cd" * 32,
        )
        raw = reconcile.authority_json_bytes(document)
    finally:
        cohort.close()
    temporary = tempfile.TemporaryDirectory(
        prefix="fp008-continuation-authority-"
    )
    directory = Path(temporary.name).resolve()
    os.chmod(directory, 0o700)
    path = directory / "approved.json"
    path.write_bytes(raw)
    os.chmod(path, 0o600)
    signature_path = directory / "approved.sig"
    signature = b"\0" * reconcile.ED25519_SIGNATURE_BYTE_COUNT
    signature_path.write_bytes(signature)
    os.chmod(signature_path, 0o600)
    guard = None
    try:
        with mock.patch.object(reconcile, "_verify_reviewer_signature"):
            guard = reconcile.AddedAuthorityManifestGuard.capture(
                ROOT,
                path,
                reconcile.sha256_bytes(raw),
                len(raw),
                signature_path,
                reconcile.sha256_bytes(signature),
                len(signature),
            )
            prepared = reconcile.prepare(ROOT, guard)
    except BaseException as exc:
        if guard is not None:
            guard.close(exc)
        temporary.cleanup()
        raise
    return prepared, temporary


class WalkSafeProjectContinuationV24Test(unittest.TestCase):
    def test_seq39_authorization_request_is_exact_and_pending(self) -> None:
        errors = (
            continuation
            .validate_seq39_canonical_binding_authorization_request(ROOT)
        )
        request_path = (
            ROOT / continuation.V24_SEQ39_AUTHORIZATION_REQUEST_RELATIVE
        )
        request = continuation.load_json(request_path)

        self.assertEqual(errors, [])
        self.assertEqual(request["status"], "AWAITING_USER_AUTHORIZATION")
        self.assertFalse(
            request["claim_boundary"]["authorization_currently_granted"]
        )
        self.assertEqual(
            continuation.canonical_json_sha256(
                request["authorization_scope"]["exact_binding_updates"]
            ),
            continuation.V24_SEQ39_EXACT_BINDING_UPDATES_SHA256,
        )
        self.assertEqual(
            continuation.canonical_json_sha256(
                request["authorization_scope"]
            ),
            continuation.V24_SEQ39_AUTHORIZATION_SCOPE_SHA256,
        )
        self.assertEqual(
            request["response_contract"]["question_utf8"],
            continuation.V24_SEQ39_AUTHORIZATION_QUESTION,
        )

    def test_seq39_authorization_request_tamper_is_rejected(self) -> None:
        request_path = (
            ROOT / continuation.V24_SEQ39_AUTHORIZATION_REQUEST_RELATIVE
        )
        review_path = (
            ROOT
            / continuation.V24_SEQ39_AUTHORIZATION_REQUEST_REVIEW_RELATIVE
        )
        original_sha256 = continuation.sha256_file

        for target, expected_error in (
            (
                request_path,
                "v2.4 seq39 authorization request SHA-256 differs",
            ),
            (
                review_path,
                (
                    "v2.4 seq39 authorization request review "
                    "SHA-256 differs"
                ),
            ),
        ):
            with self.subTest(target=target):
                def tampered_sha256(path: Path) -> str:
                    if path == target:
                        return "0" * 64
                    return original_sha256(path)

                with mock.patch.object(
                    continuation,
                    "sha256_file",
                    side_effect=tampered_sha256,
                ):
                    errors = (
                        continuation
                        .validate_seq39_canonical_binding_authorization_request(
                            ROOT
                        )
                    )
                self.assertIn(expected_error, errors)

        original_load = continuation.load_json

        def overbroad_scope(path: Path) -> dict:
            document = original_load(path)
            if path == request_path:
                document = copy.deepcopy(document)
                document["authorization_scope"][
                    "artifact_status_or_count_change_authorized"
                ] = True
            return document

        with mock.patch.object(
            continuation,
            "load_json",
            side_effect=overbroad_scope,
        ):
            errors = (
                continuation
                .validate_seq39_canonical_binding_authorization_request(
                    ROOT
                )
            )
        self.assertIn(
            "v2.4 seq39 authorization request authorization scope differs",
            errors,
        )

        checkpoint = continuation.load_json(CHECKPOINT)
        checkpoint["goal_execution"]["transition_history"][38][
            "canonical_binding_snapshot_after"
        ]["ARTIFACT_REGISTER"]["file_sha256"] = "0" * 64
        errors = (
            continuation
            .validate_seq39_canonical_binding_authorization_request(
                ROOT,
                checkpoint,
            )
        )
        self.assertIn(
            (
                "v2.4 seq39 authorization event binding differs: "
                "ARTIFACT_REGISTER"
            ),
            errors,
        )

    def test_seq39_authorization_receipt_and_historical_projection_are_exact(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        authorization_path = (
            ROOT / continuation.V24_SEQ39_AUTHORIZATION_RELATIVE
        )
        authorization = continuation.load_json(authorization_path)

        self.assertEqual(
            continuation.validate_seq39_canonical_binding_update(
                ROOT,
                checkpoint,
            ),
            [],
        )
        self.assertEqual(
            authorization,
            continuation.expected_seq39_authorization_receipt(),
        )
        self.assertEqual(
            authorization["user_response"],
            {
                "literal_utf8": "승인합니다",
                "canonicalization": "UTF-8_WITHOUT_TRAILING_NEWLINE",
                "sha256": continuation.V24_SEQ39_ACCEPTED_RESPONSE_SHA256,
            },
        )
        state = checkpoint["goal_execution"]
        self.assertEqual(checkpoint["schema_version"], "1.25.0")
        self.assertGreaterEqual(len(state["transition_history"]), 39)
        self.assertEqual(
            state["transition_history"][38]["event_id"],
            continuation.V24_SEQ39_EVENT_ID,
        )
        self.assertEqual(
            state["transition_history"][38],
            continuation.expected_seq39_event_from_history_prefix(
                checkpoint,
                continuation.sha256_file(authorization_path),
            ),
        )

    def test_seq39_historical_prefix_survives_fp048_suffix(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)

        self.assertEqual(
            continuation.validate_seq39_canonical_binding_update(
                ROOT,
                checkpoint,
            ),
            [],
        )
        tampered = copy.deepcopy(checkpoint)
        tampered["goal_execution"]["transition_history"][38][
            "previous_event_sha256"
        ] = "0" * 64
        self.assertIn(
            "v2.4 seq39 event differs",
            continuation.validate_seq39_canonical_binding_update(
                ROOT,
                tampered,
            ),
        )

    def test_fp048_materialization_projection_is_deterministic(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        materialized = state["transition_history"][39]
        ready = state["transition_history"][40]
        self.assertEqual(
            [materialized["event_id"], ready["event_id"]],
            [fp048_goal.MATERIALIZED_EVENT_ID, fp048_goal.READY_EVENT_ID],
        )
        self.assertEqual(
            materialized["event_sha256"],
            continuation.event_sha256(materialized),
        )
        self.assertEqual(ready["event_sha256"], continuation.event_sha256(ready))
        self.assertEqual(
            ready["previous_event_sha256"],
            materialized["event_sha256"],
        )
        self.assertEqual(
            ready["readiness_basis"]["predecessor_completion_event_sha256"],
            fp048_goal.PREDECESSOR_COMPLETION_EVENT_SHA256,
        )

    def test_seq39_projection_tamper_is_rejected(self) -> None:
        for mutation in (
            "authorization_binding",
            "queue_partition",
            "canonical_extra",
            "previous_hash",
        ):
            with self.subTest(mutation=mutation):
                checkpoint = continuation.load_json(CHECKPOINT)
                state = checkpoint["goal_execution"]
                event = state["transition_history"][38]
                if mutation == "authorization_binding":
                    event[
                        "canonical_binding_update_authorization_binding"
                    ]["file_sha256"] = "0" * 64
                elif mutation == "queue_partition":
                    event["runtime_after"]["artifact_work_queue"][
                        "counts_by_status"
                    ][
                        "WAITING_TRIGGER"
                    ] += 1
                elif mutation == "canonical_extra":
                    event["canonical_binding_snapshot_after"][
                        "REQUIREMENTS_TRACEABILITY"
                    ]["file_sha256"] = "0" * 64
                else:
                    event["previous_event_sha256"] = "0" * 64
                errors = (
                    continuation.validate_seq39_canonical_binding_update(
                        ROOT,
                        checkpoint,
                    )
                )
                self.assertTrue(errors, mutation)

    def test_current_v24_checkpoint_is_valid_after_seq39(self) -> None:
        errors = continuation.validate(
            ROOT,
            CHECKPOINT,
            V23_ARCHIVE,
            V24_MANIFEST,
        )
        if errors == ["v2.4 working snapshot content-set SHA-256 differs"]:
            from scripts import (
                reconcile_walksafe_fp008_session_snapshot_seq47_20260808
                as original_reconcile,
            )
            from scripts import (
                reconcile_walksafe_fp008_isolated_snapshot_fix_seq47a_20260809
                as corrective,
            )
            checkpoint_sha256 = sha256_file(CHECKPOINT)
            corrective_source = (
                checkpoint_sha256 == corrective.engine.SOURCE_CHECKPOINT_SHA256
            )
            prepared = None
            authority_temporary = None
            if corrective_source:
                projected = project_fp008_corrective_candidate(corrective)
            else:
                prepared, authority_temporary = (
                    prepare_fp008_reconcile_with_independent_authority(
                        original_reconcile
                    )
                )
                projected = prepared.projected
            real_load_json = continuation.load_json

            def load_candidate(path: Path):
                if path == CHECKPOINT:
                    return copy.deepcopy(projected)
                return real_load_json(path)

            try:
                with mock.patch.object(
                    continuation,
                    "load_json",
                    side_effect=load_candidate,
                ):
                    errors = continuation.validate(
                        ROOT,
                        CHECKPOINT,
                        V23_ARCHIVE,
                        V24_MANIFEST,
                    )
            finally:
                if prepared is not None and authority_temporary is not None:
                    prepared.cohort.close()
                    prepared.added_authority.close()
                    authority_temporary.cleanup()
        self.assertEqual(errors, [])

    def test_frozen_v23_boundary_is_byte_exact(self) -> None:
        errors, archived = continuation.validate_frozen_v23_boundary(
            ROOT,
            V23_ARCHIVE,
        )

        self.assertEqual(errors, [])
        self.assertEqual(
            sha256_file(V23_ARCHIVE),
            EXPECTED_V23_CHECKPOINT_RAW_SHA256,
        )
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
        self.assertEqual(state["focus_goal_id"], FP011_GOAL_ID)
        self.assertEqual(state["status_by_goal"][FP011_GOAL_ID], "READY")
        for relative, expected in (
            EXPECTED_V23_FROZEN_SOURCE_SHA256.items()
        ):
            self.assertEqual(sha256_file(ROOT / relative), expected, relative)

    def test_v23_archive_byte_tampering_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            tampered = Path(temporary) / "v23-tampered-test.json"
            tampered.write_bytes(V23_ARCHIVE.read_bytes() + b" ")
            with self.subTest(boundary="archive_raw_sha256"):
                errors, _ = continuation.validate_frozen_v23_boundary(
                    ROOT,
                    tampered,
                )

        self.assertTrue(
            any(
                "v2.3 archive" in error
                and ("SHA-256" in error or "missing" in error)
                for error in errors
            ),
            errors,
        )

    def test_v23_supersession_record_binds_exact_predecessor(self) -> None:
        record = continuation.load_json(V23_SUPERSESSION_RECORD)
        superseded = record["superseded_package"]

        self.assertEqual(
            superseded["package_id"],
            "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-3",
        )
        self.assertEqual(superseded["plan_version"], "2.3.0")
        self.assertEqual(
            superseded["manifest_sha256"],
            EXPECTED_V23_MANIFEST_SHA256,
        )
        self.assertEqual(
            superseded["archived_checkpoint_raw_sha256"],
            EXPECTED_V23_CHECKPOINT_RAW_SHA256,
        )
        self.assertEqual(
            superseded["transition_event_count"],
            EXPECTED_V23_EVENT_COUNT,
        )
        self.assertEqual(
            superseded["transition_history_anchor_sha256"],
            EXPECTED_V23_TAIL_SHA256,
        )
        package_set = record["superseded_package_set"]
        self.assertEqual(
            package_set,
            {
                "managed_path_count": EXPECTED_V23_PACKAGE_PATH_COUNT,
                "path_set_sha256": EXPECTED_V23_PACKAGE_PATH_SET_SHA256,
                "content_set_sha256": (
                    EXPECTED_V23_PACKAGE_CONTENT_SET_SHA256
                ),
            },
        )
        self.assertEqual(record["successor_package_id"], V24_PACKAGE_ID)
        self.assertEqual(record["successor_plan_version"], V24_PLAN_VERSION)

    def test_sequence1_is_prepared_from_exact_v23_tail(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        prepared = history[0]
        runtime = prepared["runtime_after"]
        predecessor = prepared["active_predecessor_import"]

        self.assertEqual(state["package_id"], V24_PACKAGE_ID)
        self.assertEqual(state["static_plan_version"], V24_PLAN_VERSION)
        self.assertGreaterEqual(len(history), 1)
        self.assertEqual(prepared["sequence"], 1)
        self.assertEqual(prepared["event_type"], "PACKAGE_PREPARED")
        self.assertEqual(prepared["previous_event_sha256"], "")
        self.assertEqual(
            prepared["supersedes_event_sha256"],
            EXPECTED_V23_TAIL_SHA256,
        )
        self.assertEqual(prepared["focus_goal_id"], FP011_GOAL_ID)
        self.assertEqual(prepared["to_status"], "READY")
        self.assertEqual(
            prepared["static_plan_manifest_sha256"],
            sha256_file(V24_MANIFEST),
        )
        self.assertEqual(
            predecessor["source_checkpoint_raw_sha256"],
            EXPECTED_V23_CHECKPOINT_RAW_SHA256,
        )
        self.assertEqual(
            predecessor["source_transition_event_count"],
            EXPECTED_V23_EVENT_COUNT,
        )
        self.assertEqual(
            predecessor["source_transition_history_anchor_sha256"],
            EXPECTED_V23_TAIL_SHA256,
        )
        self.assertEqual(runtime["focus_goal_id"], FP011_GOAL_ID)
        self.assertEqual(
            runtime["activation_status"],
            "READY_NOT_ACTIVATED",
        )
        self.assertEqual(
            runtime["package_status"],
            "PREPARED_NOT_ACTIVATED",
        )
        self.assertEqual(
            prepared["status_changes"][FP011_GOAL_ID],
            "READY",
        )

    def test_activation_literal_is_exactly_bound_to_candidate_hashes(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        prepared_sha256 = checkpoint["goal_execution"][
            "transition_history"
        ][0]["event_sha256"]
        expected = (
            f"{V24_PACKAGE_ID}의 manifest SHA-256 "
            f"{sha256_file(V24_MANIFEST)} 및 PACKAGE_PREPARED seq1 SHA-256 "
            f"{prepared_sha256}에 결속해 활성화를 승인합니다."
        )

        self.assertEqual(
            continuation.EXPECTED_V24_ACTIVATION_AUTHORIZATION_LITERAL,
            expected,
        )

    def test_sequence1_manifest_or_predecessor_tampering_is_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        archived = continuation.load_json(V23_ARCHIVE)
        cases = {
            "manifest": (
                "static_plan_manifest_sha256",
                "0" * 64,
            ),
            "predecessor_tail": (
                "supersedes_event_sha256",
                "0" * 64,
            ),
        }

        for label, (field, value) in cases.items():
            with self.subTest(field=label):
                tampered = copy.deepcopy(checkpoint)
                event = tampered["goal_execution"]["transition_history"][0]
                event[field] = value
                self._reseal_history(
                    tampered["goal_execution"]["transition_history"]
                )
                errors = continuation.validate_transition_replay(
                    ROOT,
                    tampered,
                    archived,
                    V24_MANIFEST,
                )
                self.assertTrue(errors, label)

    def test_sequence2_activation_is_exactly_hash_bound_if_present(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        history = checkpoint["goal_execution"]["transition_history"]
        if len(history) < 2:
            self.skipTest("v2.4 is prepared but not yet hash-bound/activated")

        prepared, activated = history[:2]
        self.assertEqual(activated["sequence"], 2)
        self.assertEqual(activated["event_type"], "PACKAGE_ACTIVATED")
        self.assertEqual(activated["previous_event_sha256"], prepared["event_sha256"])
        self.assertEqual(activated["status_changes"], {})
        binding = activated["package_activation_authorization_binding"]
        authorization_path = ROOT / binding["path"]
        self.assertEqual(
            binding["file_sha256"],
            sha256_file(authorization_path),
        )
        authorization = continuation.load_json(authorization_path)
        scope = authorization["authorization_scope"]
        user_response = authorization["user_response"]
        literal = user_response["literal_utf8"]
        literal_hash = sha256_bytes(literal.encode("utf-8"))

        self.assertEqual(authorization["status"], "AUTHORIZED")
        self.assertEqual(authorization["package_id"], V24_PACKAGE_ID)
        self.assertEqual(
            literal,
            continuation.EXPECTED_V24_ACTIVATION_AUTHORIZATION_LITERAL,
        )
        self.assertEqual(
            scope["static_plan_manifest_sha256"],
            sha256_file(V24_MANIFEST),
        )
        self.assertEqual(
            scope["initial_event_sha256"],
            prepared["event_sha256"],
        )
        self.assertEqual(scope["focus_goal_id"], FP011_GOAL_ID)
        self.assertIn(V24_PACKAGE_ID, literal)
        self.assertIn(sha256_file(V24_MANIFEST), literal)
        self.assertIn(prepared["event_sha256"], literal)
        self.assertEqual(user_response["sha256"], literal_hash)
        self.assertEqual(
            authorization["activation_request"]["request_sha256"],
            literal_hash,
        )

    def test_sequence2_without_authorization_binding_is_rejected_if_present(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        archived = continuation.load_json(V23_ARCHIVE)
        history = checkpoint["goal_execution"]["transition_history"]
        if len(history) < 2:
            self.skipTest("v2.4 activation has not been committed")
        tampered = copy.deepcopy(checkpoint)
        tampered_history = tampered["goal_execution"]["transition_history"]
        tampered_history[1].pop(
            "package_activation_authorization_binding",
            None,
        )
        self._reseal_history(tampered_history)

        errors = continuation.validate_transition_replay(
            ROOT,
            tampered,
            archived,
            V24_MANIFEST,
        )

        self.assertTrue(
            any("activation" in error.lower() for error in errors),
            errors,
        )

    def test_sequence3_fp011_start_gate_is_exact_if_present(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        history = checkpoint["goal_execution"]["transition_history"]
        if len(history) < 3:
            self.skipTest("v2.4 FP011 full start gate has not been committed")

        activated, started = history[1:3]
        self.assertEqual(started["sequence"], 3)
        self.assertEqual(started["event_type"], "GOAL_STARTED")
        self.assertEqual(started["subject_goal_id"], FP011_GOAL_ID)
        self.assertEqual(started["from_status"], "READY")
        self.assertEqual(started["to_status"], "IN_PROGRESS")
        self.assertEqual(
            started["status_changes"],
            {FP011_GOAL_ID: "IN_PROGRESS"},
        )
        self.assertEqual(
            started["previous_event_sha256"],
            activated["event_sha256"],
        )
        binding = started["implementation_start_gate_binding"]
        receipt_path = ROOT / binding["path"]
        self.assertEqual(binding["file_sha256"], sha256_file(receipt_path))
        receipt = continuation.load_json(receipt_path)
        self.assertEqual(receipt["status"], "PASS")
        self.assertEqual(receipt["package_id"], V24_PACKAGE_ID)
        self.assertEqual(
            receipt["target_transition_event_id"],
            started["event_id"],
        )
        self.assertEqual(receipt["target_goal_id"], FP011_GOAL_ID)
        self.assertEqual(
            receipt["static_plan_manifest_sha256"],
            sha256_file(V24_MANIFEST),
        )
        self.assertEqual(
            receipt["source_activation_event_sha256"],
            activated["event_sha256"],
        )
        self.assertEqual(len(receipt["check_runs"]), 19)
        self.assertTrue(
            all(run["exit_code"] == 0 for run in receipt["check_runs"])
        )
        self.assertIsInstance(started["repository_snapshot_before"], dict)

    def test_generic_next_goal_order_is_id_independent(self) -> None:
        history = self._generic_history_fixture()

        errors = continuation.validate_generic_event_order(history)

        self.assertEqual(errors, [])
        self.assertEqual(history[5]["materialized_goal_id"], "WS-ARBITRARY-NEXT")
        self.assertEqual(history[7]["subject_goal_id"], "WS-ARBITRARY-NEXT")

    def test_generic_next_goal_out_of_order_transition_is_rejected(self) -> None:
        history = self._generic_history_fixture()
        history[3]["event_type"] = "GOAL_COMPLETED"
        self._reseal_history(history)

        errors = continuation.validate_generic_event_order(history)

        self.assertTrue(errors)

    def test_generic_next_goal_subject_mismatch_is_rejected(self) -> None:
        history = self._generic_history_fixture()
        history[7]["subject_goal_id"] = "WS-WRONG-NEXT"
        history[7]["status_changes"] = {"WS-WRONG-NEXT": "IN_PROGRESS"}
        self._reseal_history(history)

        errors = continuation.validate_generic_event_order(history)

        self.assertTrue(errors)

    def test_generic_next_goal_hash_chain_tampering_is_rejected(self) -> None:
        history = self._generic_history_fixture()
        history[6]["previous_event_sha256"] = "0" * 64

        errors = continuation.validate_generic_event_order(history)

        self.assertTrue(
            any("SHA-256" in error or "hash" in error.lower() for error in errors),
            errors,
        )

    def test_latest_canonical_snapshot_supersedes_historical_physical_sha(
        self,
    ) -> None:
        checkpoint = self._checkpoint_with_current_latest_canonical_bindings()

        errors = self._validate_transition_replay(checkpoint)

        self.assertEqual(errors, [])

    def test_historical_canonical_snapshot_seal_tampering_is_rejected(
        self,
    ) -> None:
        checkpoint = self._checkpoint_with_current_latest_canonical_bindings()
        history = checkpoint["goal_execution"]["transition_history"]
        historical = next(
            event
            for event in history[:-1]
            if "canonical_binding_snapshot_after" in event
        )
        historical["canonical_binding_snapshot_after"][
            "ARTIFACT_REGISTER"
        ]["file_sha256"] = "0" * 64

        errors = self._validate_transition_replay(checkpoint)

        self.assertTrue(
            any("event 1 seal differs" in error for error in errors),
            errors,
        )

    def test_latest_canonical_snapshot_physical_sha_drift_is_rejected(
        self,
    ) -> None:
        checkpoint = self._checkpoint_with_current_latest_canonical_bindings()
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        latest_index = max(
            index
            for index, event in enumerate(history)
            if "canonical_binding_snapshot_after" in event
        )
        latest = history[latest_index]
        latest_label = f"event {latest['sequence']}"
        role = "ARTIFACT_REGISTER"
        latest["canonical_binding_snapshot_after"][role][
            "file_sha256"
        ] = "0" * 64
        for binding in checkpoint["canonical_bindings"]:
            if binding["role"] == role:
                binding["file_sha256"] = "0" * 64
                break
        for index in range(latest_index, len(history)):
            if index:
                history[index]["previous_event_sha256"] = history[index - 1][
                    "event_sha256"
                ]
            history[index]["event_sha256"] = continuation.event_sha256(
                history[index]
            )
        state["transition_history_anchor_sha256"] = history[-1]["event_sha256"]

        errors = self._validate_transition_replay(checkpoint)

        self.assertTrue(
            any(
                f"{latest_label} canonical binding differs: ARTIFACT_REGISTER"
                in error
                for error in errors
            ),
            errors,
        )

    def test_generic_transition_rejects_wrong_from_to_boundary(self) -> None:
        history = self._generic_history_fixture()
        history[2]["from_status"] = "PLANNED"
        self._reseal_history(history)

        errors = continuation.validate_generic_event_order(history)

        self.assertTrue(
            any("from/to" in error for error in errors),
            errors,
        )

    def test_generic_transition_rejects_events_after_package_completion(
        self,
    ) -> None:
        history = self._generic_history_fixture()
        current = "WS-ARBITRARY-NEXT"
        completed = self._event(
            len(history) + 1,
            "PACKAGE_COMPLETED",
            previous=history[-1]["event_sha256"],
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            status_changes={current: "COMPLETE_AT_TARGET"},
            subject_goal_id=current,
        )
        history.append(completed)
        history.append(
            self._event(
                len(history) + 1,
                "GOAL_MATERIALIZED",
                previous=completed["event_sha256"],
                from_status="",
                to_status="PLANNED",
                status_changes={"WS-ILLEGAL-AFTER-COMPLETE": "PLANNED"},
                materialized_goal_id="WS-ILLEGAL-AFTER-COMPLETE",
                predecessor_goal_id=current,
            )
        )

        errors = continuation.validate_generic_event_order(history)

        self.assertTrue(
            any("terminal PACKAGE_COMPLETED" in error for error in errors),
            errors,
        )

    def test_gate_validator_rejects_missing_check_runs(self) -> None:
        errors, payload = continuation._validate_check_runs(
            ROOT,
            label="synthetic gate",
            event_id="WS-SYNTHETIC-GATE-001",
            receipt={
                "execution_window": {
                    "started_at": "2026-07-25T05:00:00+09:00",
                    "ended_at": "2026-07-25T05:00:01+09:00",
                },
                "generated_at": "2026-07-25T05:00:02+09:00",
            },
            expected_checks=[
                {"check_id": "ONE", "command": "true"},
                {"check_id": "TWO", "command": "true"},
            ],
        )

        self.assertIsNone(payload)
        self.assertTrue(any("count differs" in error for error in errors))

    def test_runtime_binding_amendment_accepts_only_sealed_receipts(self) -> None:
        self.assertEqual(
            set(continuation.START_GATE_RUNTIME_BINDING_AMENDMENTS),
            {
                FP008_START_GATE_EVENT_ID,
                FP008_RESUME_GATE_EVENT_ID,
                FP046_START_GATE_EVENT_ID,
            },
        )
        for amendment in (
            continuation.START_GATE_RUNTIME_BINDING_AMENDMENTS.values()
        ):
            self.assertEqual(
                amendment,
                {
                    GRADLE_VERIFICATION_METADATA_RELATIVE: {
                        "sealed_sha256": (
                            GRADLE_VERIFICATION_METADATA_SEALED_SHA256
                        ),
                        "current_sha256": (
                            GRADLE_VERIFICATION_METADATA_CURRENT_SHA256
                        ),
                    }
                },
            )
        cases = (
            (
                FP008_START_GATE_EVENT_ID,
                FP008_START_GATE_RECEIPT,
                continuation._validate_fp008_runtime_bindings,
            ),
            (
                FP008_RESUME_GATE_EVENT_ID,
                FP008_RESUME_GATE_RECEIPT,
                continuation._validate_fp008_runtime_bindings,
            ),
            (
                FP046_START_GATE_EVENT_ID,
                FP046_START_GATE_RECEIPT,
                continuation._validate_fp046_runtime_bindings,
            ),
        )
        for event_id, receipt_path, validator in cases:
            with self.subTest(receipt=receipt_path.name):
                bindings = continuation.load_json(receipt_path)[
                    "runtime_bindings"
                ]
                self.assertEqual(
                    bindings[2],
                    {
                        "path": GRADLE_VERIFICATION_METADATA_RELATIVE,
                        "file_sha256": (
                            GRADLE_VERIFICATION_METADATA_SEALED_SHA256
                        ),
                    },
                )
                self.assertEqual(
                    validator(ROOT, bindings, event_id=event_id),
                    [],
                )

    def test_runtime_binding_amendment_does_not_change_new_gate_rules(
        self,
    ) -> None:
        bindings = continuation.load_json(FP008_START_GATE_RECEIPT)[
            "runtime_bindings"
        ]
        current_bindings = copy.deepcopy(bindings)
        current_bindings[2]["file_sha256"] = (
            GRADLE_VERIFICATION_METADATA_CURRENT_SHA256
        )
        synthetic_event_id = "WS-SYNTHETIC-NEW-FP008-START-GATE"

        self.assertTrue(
            continuation._validate_fp008_runtime_bindings(
                ROOT,
                bindings,
                event_id=synthetic_event_id,
            )
        )
        self.assertEqual(
            continuation._validate_fp008_runtime_bindings(
                ROOT,
                current_bindings,
                event_id=synthetic_event_id,
            ),
            [],
        )

    def test_runtime_binding_amendment_rejects_receipt_tampering(self) -> None:
        bindings = continuation.load_json(FP008_START_GATE_RECEIPT)[
            "runtime_bindings"
        ]
        mutations = {}
        successor = copy.deepcopy(bindings)
        successor[2]["file_sha256"] = (
            GRADLE_VERIFICATION_METADATA_CURRENT_SHA256
        )
        mutations["successor substituted into sealed receipt"] = successor
        arbitrary = copy.deepcopy(bindings)
        arbitrary[2]["file_sha256"] = "0" * 64
        mutations["arbitrary metadata digest"] = arbitrary
        reordered = copy.deepcopy(bindings)
        reordered[0], reordered[1] = reordered[1], reordered[0]
        mutations["reordered bindings"] = reordered
        other_path = copy.deepcopy(bindings)
        other_path[0]["file_sha256"] = (
            GRADLE_VERIFICATION_METADATA_SEALED_SHA256
        )
        mutations["amendment reused for other path"] = other_path

        for label, value in mutations.items():
            with self.subTest(label=label):
                self.assertTrue(
                    continuation._validate_fp008_runtime_bindings(
                        ROOT,
                        value,
                        event_id=FP008_START_GATE_EVENT_ID,
                    )
                )

    def test_runtime_binding_amendment_rejects_unapproved_live_digest(
        self,
    ) -> None:
        bindings = continuation.load_json(FP046_START_GATE_RECEIPT)[
            "runtime_bindings"
        ]
        real_sha256_file = continuation.sha256_file

        def changed_sha256(path: Path) -> str:
            if path == ROOT / GRADLE_VERIFICATION_METADATA_RELATIVE:
                return "f" * 64
            return real_sha256_file(path)

        with mock.patch.object(
            continuation,
            "sha256_file",
            side_effect=changed_sha256,
        ):
            errors = continuation._validate_fp046_runtime_bindings(
                ROOT,
                bindings,
                event_id=FP046_START_GATE_EVENT_ID,
            )

        self.assertTrue(
            any("current runtime binding" in error for error in errors),
            errors,
        )

    @staticmethod
    def _create_fp008_private_gate_fixture(
        root: Path,
        event_id: str,
    ) -> tuple[
        Path,
        dict[str, str],
        list[dict[str, str]],
        dict,
    ]:
        event_dir = (
            root
            / continuation.FP008_GATE_ROOT_RELATIVE
            / event_id
        )
        event_dir.mkdir(parents=True)
        os.chmod(event_dir, 0o700)
        receipt_content = b'{"document_id":"WS-FP008-TEST-RECEIPT"}\n'
        receipt_path = (
            event_dir / "implementation-start-gate-receipt.json"
        )
        receipt_path.write_bytes(receipt_content)
        os.chmod(receipt_path, 0o600)
        binding = {
            "document_id": "WS-FP008-TEST-RECEIPT",
            "path": receipt_path.relative_to(root).as_posix(),
            "file_sha256": sha256_bytes(receipt_content),
        }
        expected_checks = [
            {"check_id": check_id, "command": "true"}
            for check_id in continuation.FP008_START_GATE_CHECK_IDS
        ]
        check_runs = []
        for index, expected in enumerate(expected_checks, start=1):
            check_id = expected["check_id"]
            content = b"{}\n" if check_id == "REPOSITORY_STATE" else b"PASS\n"
            relative = (
                continuation.FP008_GATE_ROOT_RELATIVE
                / event_id
                / f"{index:02d}-{check_id}.log"
            )
            output_path = root / relative
            output_path.write_bytes(content)
            os.chmod(output_path, 0o600)
            check_runs.append(
                {
                    "check_id": check_id,
                    "command": "true",
                    "executed_at": (
                        f"2026-08-03T05:00:{index:02d}+09:00"
                    ),
                    "exit_code": 0,
                    "output_path": relative.as_posix(),
                    "output_sha256": sha256_bytes(content),
                }
            )
        run_receipt = {
            "execution_window": {
                "started_at": "2026-08-03T05:00:00+09:00",
                "ended_at": "2026-08-03T05:00:10+09:00",
            },
            "generated_at": "2026-08-03T05:00:11+09:00",
            "check_runs": check_runs,
        }
        return event_dir, binding, expected_checks, run_receipt

    def test_fp008_private_gate_layout_accepts_exact_evidence(self) -> None:
        event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-901"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, binding, expected_checks, run_receipt = (
                self._create_fp008_private_gate_fixture(root, event_id)
            )
            binding_errors, payload, _, directory_identity = (
                continuation._load_fp008_private_binding(
                    root,
                    binding,
                    event_id=event_id,
                    expected_path=binding["path"],
                    label="synthetic FP008 receipt",
                )
            )
            run_errors, _ = continuation._validate_check_runs(
                root,
                label="synthetic FP008 gate",
                event_id=event_id,
                receipt=run_receipt,
                expected_checks=expected_checks,
                fp008_private_evidence=True,
                fp008_event_directory_identity=directory_identity,
            )

        self.assertEqual(binding_errors, [])
        self.assertEqual(payload["document_id"], binding["document_id"])
        self.assertEqual(run_errors, [])

    def test_fp008_private_receipt_rejects_unsafe_file_types(self) -> None:
        event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-902"
        cases = {
            "symlink": "contains a symlink",
            "wrong mode": "mode differs from 0600",
            "hardlink": "link count differs from 1",
            "nonregular": "is not a regular file",
        }
        for case, expected_error in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                event_dir, binding, _, _ = (
                    self._create_fp008_private_gate_fixture(root, event_id)
                )
                receipt_path = root / binding["path"]
                if case == "symlink":
                    target = root / "receipt-target.json"
                    target.write_bytes(receipt_path.read_bytes())
                    os.chmod(target, 0o600)
                    receipt_path.unlink()
                    receipt_path.symlink_to(target)
                elif case == "wrong mode":
                    os.chmod(receipt_path, 0o640)
                elif case == "hardlink":
                    target = root / "receipt-target.json"
                    receipt_path.rename(target)
                    os.link(target, receipt_path)
                else:
                    receipt_path.unlink()
                    receipt_path.mkdir()
                    os.chmod(receipt_path, 0o600)

                errors, payload, _, _ = (
                    continuation._load_fp008_private_binding(
                        root,
                        binding,
                        event_id=event_id,
                        expected_path=(
                            event_dir.relative_to(root)
                            / "implementation-start-gate-receipt.json"
                        ).as_posix(),
                        label="synthetic FP008 receipt",
                    )
                )

                self.assertEqual(payload, {})
                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_fp008_private_gate_directory_is_real_private_and_link_free(
        self,
    ) -> None:
        event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-906"
        cases = {
            "event directory symlink": "path contains a symlink",
            "intermediate symlink": "path contains a symlink",
            "wrong mode": "event directory mode differs from 0700",
        }
        for case, expected_error in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                fixture_root = root / "backing" if case == "intermediate symlink" else root
                event_dir, binding, _, _ = (
                    self._create_fp008_private_gate_fixture(
                        fixture_root,
                        event_id,
                    )
                )
                if case == "event directory symlink":
                    target = root / "event-directory-target"
                    event_dir.rename(target)
                    event_dir.symlink_to(target, target_is_directory=True)
                elif case == "intermediate symlink":
                    (root / "docs").symlink_to(
                        fixture_root / "docs",
                        target_is_directory=True,
                    )
                else:
                    os.chmod(event_dir, 0o750)

                errors, payload, _, _ = (
                    continuation._load_fp008_private_binding(
                        root,
                        binding,
                        event_id=event_id,
                        expected_path=binding["path"],
                        label="synthetic FP008 receipt",
                    )
                )

                self.assertEqual(payload, {})
                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_fp008_private_logs_reject_unsafe_file_types(self) -> None:
        event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-903"
        cases = {
            "symlink": "contains a symlink",
            "wrong mode": "mode differs from 0600",
            "hardlink": "link count differs from 1",
            "nonregular": "is not a regular file",
        }
        for case, expected_error in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                _, binding, expected_checks, run_receipt = (
                    self._create_fp008_private_gate_fixture(root, event_id)
                )
                _, _, _, directory_identity = (
                    continuation._load_fp008_private_binding(
                        root,
                        binding,
                        event_id=event_id,
                        expected_path=binding["path"],
                        label="synthetic FP008 receipt",
                    )
                )
                log_path = root / run_receipt["check_runs"][0]["output_path"]
                if case == "symlink":
                    target = root / "log-target.log"
                    target.write_bytes(log_path.read_bytes())
                    os.chmod(target, 0o600)
                    log_path.unlink()
                    log_path.symlink_to(target)
                elif case == "wrong mode":
                    os.chmod(log_path, 0o640)
                elif case == "hardlink":
                    target = root / "log-target.log"
                    log_path.rename(target)
                    os.link(target, log_path)
                else:
                    log_path.unlink()
                    log_path.mkdir()
                    os.chmod(log_path, 0o600)

                errors, _ = continuation._validate_check_runs(
                    root,
                    label="synthetic FP008 gate",
                    event_id=event_id,
                    receipt=run_receipt,
                    expected_checks=expected_checks,
                    fp008_private_evidence=True,
                    fp008_event_directory_identity=directory_identity,
                )

                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_fp008_private_gate_rejects_extra_inventory_entry(self) -> None:
        event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-904"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            event_dir, binding, expected_checks, run_receipt = (
                self._create_fp008_private_gate_fixture(root, event_id)
            )
            _, _, _, directory_identity = (
                continuation._load_fp008_private_binding(
                    root,
                    binding,
                    event_id=event_id,
                    expected_path=binding["path"],
                    label="synthetic FP008 receipt",
                )
            )
            extra = event_dir / ".receipt.stage"
            extra.write_bytes(b"unpublished\n")
            os.chmod(extra, 0o600)

            errors, _ = continuation._validate_check_runs(
                root,
                label="synthetic FP008 gate",
                event_id=event_id,
                receipt=run_receipt,
                expected_checks=expected_checks,
                fp008_private_evidence=True,
                fp008_event_directory_identity=directory_identity,
            )

        self.assertTrue(
            any("only the 9 ordered logs and receipt" in error for error in errors),
            errors,
        )

    def test_fp008_private_receipt_rejects_pathname_replacement(self) -> None:
        event_id = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-905"
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, binding, _, _ = self._create_fp008_private_gate_fixture(
                root,
                event_id,
            )
            errors, _, receipt_identity, directory_identity = (
                continuation._load_fp008_private_binding(
                    root,
                    binding,
                    event_id=event_id,
                    expected_path=binding["path"],
                    label="synthetic FP008 receipt",
                )
            )
            self.assertEqual(errors, [])
            self.assertIsNotNone(receipt_identity)
            self.assertIsNotNone(directory_identity)
            receipt_path = root / binding["path"]
            old_receipt = root / "old-receipt.json"
            content = receipt_path.read_bytes()
            receipt_path.rename(old_receipt)
            receipt_path.write_bytes(content)
            os.chmod(receipt_path, 0o600)

            replacement_errors, _, _, _ = (
                continuation._read_fp008_private_event_file(
                    root,
                    event_id=event_id,
                    relative_path=binding["path"],
                    label="synthetic FP008 receipt",
                    expected_directory_identity=directory_identity,
                    expected_file_identity=receipt_identity,
                )
            )

        self.assertTrue(
            any("pathname identity differs" in error for error in replacement_errors),
            replacement_errors,
        )

    def _validate_single_silent_gate_output(
        self,
        *,
        label: str = continuation.INITIAL_START_GATE_LABEL,
        expected_check_id: str = continuation.SILENT_SUCCESS_CHECK_ID,
        expected_command: str = continuation.SILENT_SUCCESS_COMMAND,
        run_check_id: str | None = None,
        run_command: str | None = None,
        run_output_path: str | None = None,
        exit_code: int = 0,
        output_sha256: str | None = None,
        output: bytes = b"",
        executed_at_overrides: dict[int, str] | None = None,
    ) -> list[str]:
        event_id = "WS-SYNTHETIC-INITIAL-START-GATE-001"
        executed_at_overrides = executed_at_overrides or {}
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            expected_checks = []
            check_runs = []
            for index in range(1, 20):
                check_id = (
                    expected_check_id
                    if index == 15
                    else f"SYNTHETIC_{index:02d}"
                )
                command = expected_command if index == 15 else "true"
                expected_checks.append(
                    {"check_id": check_id, "command": command}
                )
                expected_output = (
                    "docs/control/execution/goal-gates/"
                    f"{event_id}/{index:02d}-{check_id}.log"
                )
                actual_output = (
                    run_output_path
                    if index == 15 and run_output_path is not None
                    else expected_output
                )
                content = output if index == 15 else b"PASS\n"
                output_path = root / actual_output
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_bytes(content)
                digest = (
                    output_sha256
                    if index == 15 and output_sha256 is not None
                    else hashlib.sha256(content).hexdigest()
                )
                check_runs.append(
                    {
                        "check_id": (
                            run_check_id
                            if index == 15 and run_check_id is not None
                            else check_id
                        ),
                        "command": (
                            run_command
                            if index == 15 and run_command is not None
                            else command
                        ),
                        "executed_at": executed_at_overrides.get(
                            index,
                            f"2026-07-25T05:00:{index - 1:02d}+09:00",
                        ),
                        "exit_code": exit_code if index == 15 else 0,
                        "output_path": actual_output,
                        "output_sha256": digest,
                    }
                )

            errors, _ = continuation._validate_check_runs(
                root,
                label=label,
                event_id=event_id,
                receipt={
                    "execution_window": {
                        "started_at": "2026-07-25T05:00:00+09:00",
                        "ended_at": "2026-07-25T05:00:19+09:00",
                    },
                    "generated_at": "2026-07-25T05:00:20+09:00",
                    "check_runs": check_runs,
                },
                expected_checks=expected_checks,
            )
        return errors

    def test_initial_start_gate_allows_exact_registry_silent_success(self) -> None:
        self.assertEqual(self._validate_single_silent_gate_output(), [])

    def test_current_repaired_initial_start_receipt_is_valid(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        event = next(
            item
            for item in checkpoint["goal_execution"][
                "transition_history"
            ]
            if item.get("implementation_start_gate_binding")
        )
        binding = event["implementation_start_gate_binding"]
        receipt = continuation.load_json(ROOT / binding["path"])
        manifest = continuation.load_json(V24_MANIFEST)
        expected_checks = continuation._control_checks(
            manifest,
            "implementation_start_gate_checks",
        )

        errors, _ = continuation._validate_check_runs(
            ROOT,
            label=continuation.INITIAL_START_GATE_LABEL,
            event_id=event["event_id"],
            receipt=receipt,
            expected_checks=expected_checks,
        )

        self.assertEqual(errors, [])

    def test_check_run_executed_at_accepts_zero_to_six_fractional_digits(
        self,
    ) -> None:
        errors = self._validate_single_silent_gate_output(
            executed_at_overrides={
                3: "2026-07-25T05:00:03+09:00",
                4: "2026-07-25T05:00:03.000001+09:00",
                5: "2026-07-25T05:00:03.000002+09:00",
                10: "2026-07-25T05:00:09.123456+09:00",
                15: "2026-07-25T05:00:15+09:00",
                16: "2026-07-25T05:00:15.000001+09:00",
            }
        )

        self.assertEqual(errors, [])

    def test_check_run_executed_at_rejects_invalid_fraction_and_order(
        self,
    ) -> None:
        cases = {
            "seven fractional digits": (
                {10: "2026-07-25T05:00:09.0000001+09:00"},
                "0 to 6 fractional digits",
            ),
            "missing timezone": (
                {10: "2026-07-25T05:00:09.000001"},
                "0 to 6 fractional digits",
            ),
            "non-increasing": (
                {10: "2026-07-25T05:00:08+09:00"},
                "time is not strictly increasing",
            ),
            "outside execution window": (
                {10: "2026-07-25T05:00:21+09:00"},
                "is outside the execution window",
            ),
        }
        for name, (overrides, expected_error) in cases.items():
            with self.subTest(name=name):
                errors = self._validate_single_silent_gate_output(
                    executed_at_overrides=overrides
                )
                self.assertTrue(
                    any(expected_error in error for error in errors),
                    errors,
                )

    def test_initial_start_gate_silent_success_tampering_is_rejected(self) -> None:
        event_id = "WS-SYNTHETIC-INITIAL-START-GATE-001"
        wrong_path = (
            "docs/control/execution/goal-gates/"
            f"{event_id}/15-WRONG.log"
        )
        cases = {
            "other empty check": {
                "expected_check_id": "OTHER_SILENT_CHECK",
            },
            "nonzero exit": {
                "exit_code": 1,
            },
            "wrong receipt command": {
                "run_command": "true",
            },
            "wrong contract command": {
                "expected_command": "true",
            },
            "wrong path": {
                "run_output_path": wrong_path,
            },
            "wrong hash": {
                "output_sha256": "0" * 64,
            },
            "non-initial gate": {
                "label": "v2.4 resume gate",
            },
        }
        for name, arguments in cases.items():
            with self.subTest(name=name):
                errors = self._validate_single_silent_gate_output(**arguments)
                self.assertTrue(
                    any("output is empty" in error for error in errors),
                    errors,
                )

    def test_nonempty_check_output_rule_is_unchanged(self) -> None:
        self.assertEqual(
            self._validate_single_silent_gate_output(
                expected_check_id="OTHER_NONEMPTY_CHECK",
                expected_command="true",
                output=b"PASS\n",
            ),
            [],
        )

    @staticmethod
    def _event(
        sequence: int,
        event_type: str,
        *,
        previous: str,
        from_status: str,
        to_status: str,
        status_changes: dict[str, str],
        **extra,
    ) -> dict:
        event = {
            "sequence": sequence,
            "event_id": f"WS-V24-GENERIC-TEST-{sequence:03d}",
            "event_type": event_type,
            "previous_event_sha256": previous,
            "from_status": from_status,
            "to_status": to_status,
            "status_changes": status_changes,
            **extra,
        }
        event["event_sha256"] = continuation.canonical_json_sha256(event)
        return event

    @classmethod
    def _generic_history_fixture(cls) -> list[dict]:
        current = FP011_GOAL_ID
        next_goal = "WS-ARBITRARY-NEXT"
        history: list[dict] = []

        def append(event_type: str, **fields) -> dict:
            event = cls._event(
                len(history) + 1,
                event_type,
                previous=history[-1]["event_sha256"] if history else "",
                **fields,
            )
            history.append(event)
            return event

        append(
            "PACKAGE_PREPARED",
            from_status="",
            to_status="READY",
            status_changes={current: "READY"},
            focus_goal_id=current,
        )
        append(
            "PACKAGE_ACTIVATED",
            from_status="READY",
            to_status="READY",
            status_changes={},
            focus_goal_id=current,
        )
        append(
            "GOAL_STARTED",
            from_status="READY",
            to_status="IN_PROGRESS",
            status_changes={current: "IN_PROGRESS"},
            subject_goal_id=current,
            focus_goal_id=current,
        )
        updated = append(
            "CANONICAL_BINDINGS_UPDATED",
            from_status="IN_PROGRESS",
            to_status="IN_PROGRESS",
            status_changes={},
            produced_by_goal_id=current,
            focus_goal_id=current,
        )
        completed = append(
            "GOAL_COMPLETED",
            from_status="IN_PROGRESS",
            to_status="COMPLETE_AT_TARGET",
            status_changes={current: "COMPLETE_AT_TARGET"},
            subject_goal_id=current,
            canonical_update_event_sha256=updated["event_sha256"],
        )
        append(
            "GOAL_MATERIALIZED",
            from_status="",
            to_status="PLANNED",
            status_changes={next_goal: "PLANNED"},
            materialized_goal_id=next_goal,
            predecessor_goal_id=current,
        )
        append(
            "GOAL_READY",
            from_status="PLANNED",
            to_status="READY",
            status_changes={next_goal: "READY"},
            subject_goal_id=next_goal,
            readiness_basis={
                "dependency_completion_events": [
                    {
                        "goal_id": current,
                        "event_sha256": completed["event_sha256"],
                    }
                ]
            },
        )
        append(
            "GOAL_STARTED",
            from_status="READY",
            to_status="IN_PROGRESS",
            status_changes={next_goal: "IN_PROGRESS"},
            subject_goal_id=next_goal,
            focus_goal_id=next_goal,
        )
        return history

    @staticmethod
    def _reseal_history(history: list[dict]) -> None:
        previous = ""
        for sequence, event in enumerate(history, start=1):
            event["sequence"] = sequence
            event["previous_event_sha256"] = previous
            event.pop("event_sha256", None)
            event["event_sha256"] = continuation.canonical_json_sha256(event)
            previous = event["event_sha256"]

    @staticmethod
    def _checkpoint_with_current_latest_canonical_bindings() -> dict:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        latest_index = max(
            index
            for index, event in enumerate(history)
            if "canonical_binding_snapshot_after" in event
        )
        latest = history[latest_index]
        snapshot = latest["canonical_binding_snapshot_after"]
        top_level_by_role = {
            binding["role"]: binding
            for binding in checkpoint["canonical_bindings"]
        }
        for role, binding in snapshot.items():
            digest = sha256_file(ROOT / binding["path"])
            binding["file_sha256"] = digest
            top_level_by_role[role]["file_sha256"] = digest
        for index in range(latest_index, len(history)):
            if index:
                history[index]["previous_event_sha256"] = history[index - 1][
                    "event_sha256"
                ]
            history[index]["event_sha256"] = continuation.event_sha256(
                history[index]
            )
        state["transition_history_anchor_sha256"] = history[-1]["event_sha256"]
        return checkpoint

    @staticmethod
    def _validate_transition_replay(checkpoint: dict) -> list[str]:
        return continuation.validate_transition_replay(
            ROOT,
            checkpoint,
            continuation.load_json(V23_ARCHIVE),
            V24_MANIFEST,
            expected_prepared_sha256=(
                continuation.EXPECTED_V24_PREPARED_EVENT_SHA256
            ),
            expected_authorization_sha256=(
                continuation.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
            ),
        )


if __name__ == "__main__":
    unittest.main()
