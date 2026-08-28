"""Focused lifecycle and frozen-predecessor regressions for Goal v2.4."""

from __future__ import annotations

import copy
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import (
    apply_walksafe_fp046_npc_r002_reopen_20260815 as r008_review,
    apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815 as r008_transaction,
    apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825 as fp046_completion,
    build_walksafe_fp046_gap_backlog_r029_20260815 as r029_bridge,
    build_walksafe_fp046_gap_backlog_r029_candidate_20260815 as r029_candidate,
    build_walksafe_fp022_completion_seq70_71_review_20260814 as r008_control_review,
)
from scripts import check_walksafe_goal_graph_v2_3 as frozen_goal_graph
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
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
FP046_PRIVACY_RIGHTS_TEST_RELATIVE = (
    "apps/android-gateway/test/privacy-rights.test.ts"
)
FP046_PRIVACY_RIGHTS_TEST_START_SHA256 = (
    "6aa105f228a98432bb6b7940dd5521200fd1455104044e50031a0e0e8a4f16da"
)
FP046_PRIVACY_RIGHTS_TEST_SEALED_SHA256 = (
    "fa49e585a2faf063250df8c88fa8fdad4dbe1f767c1d072d4c9d409d4cbc80a6"
)
FP046_PRIVACY_RIGHTS_TEST_CURRENT_SHA256 = (
    "dffc386d5183ef7e517778d676fe2dd0cfd033631817306f347cc632d6aae566"
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

    def test_generic_order_allows_ready_goal_completion_for_semantic_replay(self) -> None:
        history = self._generic_history_fixture()[:7]
        goal_id = "WS-ARBITRARY-NEXT"
        history.append(
            self._event(
                len(history) + 1,
                "GOAL_COMPLETED",
                previous=history[-1]["event_sha256"],
                from_status="READY",
                to_status="COMPLETE_AT_TARGET",
                status_changes={goal_id: "COMPLETE_AT_TARGET"},
                subject_goal_id=goal_id,
            )
        )

        self.assertEqual(continuation.validate_generic_event_order(history), [])

    def test_ready_completion_is_semantically_limited_to_workstreams(self) -> None:
        self.assertTrue(
            continuation._completion_source_is_allowed("READY", "WORKSTREAM")
        )
        self.assertFalse(
            continuation._completion_source_is_allowed("READY", "WORK_ITEM")
        )
        self.assertTrue(
            continuation._completion_source_is_allowed("IN_PROGRESS", "WORK_ITEM")
        )

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
        checkpoint = continuation.load_json(CHECKPOINT)

        errors = self._validate_transition_replay(checkpoint)

        self.assertEqual(errors, [])

    def test_historical_canonical_snapshot_seal_tampering_is_rejected(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
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
        checkpoint = continuation.load_json(CHECKPOINT)
        role = "ARTIFACT_REGISTER"
        target = (ROOT / "docs/deliverables/00-control/artifact-register.json").resolve()
        real_sha256_file = continuation.sha256_file

        with mock.patch.object(
            continuation,
            "sha256_file",
            side_effect=lambda path: (
                "0" * 64 if path == target else real_sha256_file(path)
            ),
        ):
            errors = self._validate_transition_replay(checkpoint)

        self.assertTrue(
            any(
                "canonical binding differs: ARTIFACT_REGISTER"
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

    def test_fp046_final_source_amendment_preserves_seal_and_reaches_live(
        self,
    ) -> None:
        self.assertEqual(
            goal_graph.FP046_FINAL_SOURCE_BINDING_AMENDMENTS,
            {
                FP046_PRIVACY_RIGHTS_TEST_RELATIVE: {
                    "sealed_byte_length": 19931,
                    "sealed_sha256": FP046_PRIVACY_RIGHTS_TEST_SEALED_SHA256,
                    "current_byte_length": 20540,
                    "current_sha256": FP046_PRIVACY_RIGHTS_TEST_CURRENT_SHA256,
                }
            },
        )
        implementation = continuation.load_json(
            ROOT / goal_graph.FP046_IMPLEMENTATION_PATH
        )
        sealed_rows = [
            row
            for row in implementation["final_content_manifest"]["files"]
            if row["path"] == FP046_PRIVACY_RIGHTS_TEST_RELATIVE
        ]
        self.assertEqual(len(sealed_rows), 1)
        self.assertEqual(sealed_rows[0]["byte_length"], 19931)
        self.assertEqual(
            sealed_rows[0]["sha256"],
            FP046_PRIVACY_RIGHTS_TEST_SEALED_SHA256,
        )

        errors, _, transitions = (
            goal_graph.validate_fp046_r014_successor_authority(
                ROOT,
                continuation.load_json(CHECKPOINT),
            )
        )

        self.assertEqual(errors, [])
        self.assertEqual(len(transitions), 67)
        self.assertEqual(
            transitions[FP046_PRIVACY_RIGHTS_TEST_RELATIVE],
            (
                FP046_PRIVACY_RIGHTS_TEST_START_SHA256,
                FP046_PRIVACY_RIGHTS_TEST_CURRENT_SHA256,
            ),
        )

        compatibility_path = (
            ROOT / goal_graph.FP046_FINAL_SOURCE_COMPATIBILITY_PATH
        )
        self.assertEqual(
            compatibility_path.stat().st_size,
            goal_graph.FP046_FINAL_SOURCE_COMPATIBILITY_BYTE_COUNT,
        )
        self.assertEqual(
            sha256_file(compatibility_path),
            goal_graph.FP046_FINAL_SOURCE_COMPATIBILITY_SHA256,
        )
        compatibility = continuation.load_json(compatibility_path)
        self.assertEqual(
            compatibility["record_status"],
            "NOT_GOAL_EVENT_NO_COMPLETION_CREDIT",
        )
        self.assertEqual(
            compatibility["claim_boundary"],
            {
                "actual_device_credit_added": 0,
                "formal_test_credit_added": 0,
                "goal_completion_credit_added": 0,
                "goal_event_created": False,
                "historical_control_modified": False,
                "release_credit_added": 0,
            },
        )
        self.assertEqual(
            compatibility["amendments"][0]["source_commit"],
            goal_graph.FP046_FINAL_SOURCE_SUCCESSOR_COMMIT,
        )
        committed_source = subprocess.run(
            [
                "git",
                "cat-file",
                "blob",
                (
                    goal_graph.FP046_FINAL_SOURCE_SUCCESSOR_COMMIT
                    + ":"
                    + FP046_PRIVACY_RIGHTS_TEST_RELATIVE
                ),
            ],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
        ).stdout
        self.assertEqual(len(committed_source), 20540)
        self.assertEqual(
            sha256_bytes(committed_source),
            FP046_PRIVACY_RIGHTS_TEST_CURRENT_SHA256,
        )

    def test_quality_workflow_fetches_fp046_successor_commit_before_checks(
        self,
    ) -> None:
        workflow = (ROOT / ".github/workflows/quality.yml").read_text(
            encoding="utf-8"
        )
        expected_step = f'''      - name: Fetch FP046 successor source commit
        shell: bash
        env:
          FP046_SUCCESSOR_SOURCE_COMMIT: "{goal_graph.FP046_FINAL_SOURCE_SUCCESSOR_COMMIT}"
        run: |
          set -euo pipefail
          test "${{FP046_SUCCESSOR_SOURCE_COMMIT:?}}" = "{goal_graph.FP046_FINAL_SOURCE_SUCCESSOR_COMMIT}"
          git fetch --no-tags --no-write-fetch-head --depth=1 \\
            origin "${{FP046_SUCCESSOR_SOURCE_COMMIT:?}}"
          test "$(git cat-file -t "${{FP046_SUCCESSOR_SOURCE_COMMIT:?}}")" = commit
'''

        self.assertIn(expected_step, workflow)
        self.assertLess(
            workflow.index(expected_step),
            workflow.index("Run current checkpoint control tests"),
        )

    def test_fp046_final_source_amendment_requires_compatibility_record(
        self,
    ) -> None:
        target = ROOT / goal_graph.FP046_FINAL_SOURCE_COMPATIBILITY_PATH
        real_sha256_file = goal_graph.continuation.sha256_file

        def changed_sha256(path: Path) -> str:
            if path == target:
                return "f" * 64
            return real_sha256_file(path)

        with mock.patch.object(
            goal_graph.continuation,
            "sha256_file",
            side_effect=changed_sha256,
        ):
            errors, artifact_bindings, transitions = (
                goal_graph.validate_fp046_r014_successor_authority(
                    ROOT,
                    continuation.load_json(CHECKPOINT),
                )
            )

        self.assertEqual(
            errors,
            ["FP046 final source compatibility binding differs"],
        )
        self.assertEqual(artifact_bindings, {})
        self.assertEqual(transitions, {})

    def test_fp046_final_source_amendment_rejects_third_live_digest(
        self,
    ) -> None:
        target = ROOT / FP046_PRIVACY_RIGHTS_TEST_RELATIVE
        real_sha256_file = goal_graph.continuation.sha256_file

        def changed_sha256(path: Path) -> str:
            if path == target:
                return "f" * 64
            return real_sha256_file(path)

        with mock.patch.object(
            goal_graph.continuation,
            "sha256_file",
            side_effect=changed_sha256,
        ):
            errors, artifact_bindings, transitions = (
                goal_graph.validate_fp046_r014_successor_authority(
                    ROOT,
                    continuation.load_json(CHECKPOINT),
                )
            )

        self.assertEqual(
            errors,
            [
                "FP046 final source binding differs: "
                + FP046_PRIVACY_RIGHTS_TEST_RELATIVE
            ],
        )
        self.assertEqual(artifact_bindings, {})
        self.assertEqual(transitions, {})

    def test_current_security_database_successors_preserve_completion_sources(
        self,
    ) -> None:
        amendments = (
            goal_graph.CURRENT_SECURITY_DATABASE_COMPATIBILITY_AMENDMENTS
        )
        self.assertEqual(
            goal_graph._current_security_database_compatibility_artifacts(ROOT),
            {
                relative: (
                    amendment["predecessor_sha256"],
                    amendment["successor_sha256"],
                )
                for relative, amendment in amendments.items()
            },
        )

        record_path = (
            ROOT / goal_graph.CURRENT_SECURITY_DATABASE_COMPATIBILITY_PATH
        )
        self.assertEqual(
            record_path.stat().st_size,
            goal_graph.CURRENT_SECURITY_DATABASE_COMPATIBILITY_BYTE_COUNT,
        )
        self.assertEqual(
            sha256_file(record_path),
            goal_graph.CURRENT_SECURITY_DATABASE_COMPATIBILITY_SHA256,
        )

    def test_current_security_database_successor_rejects_third_digest(
        self,
    ) -> None:
        target = ROOT / "backend/tests/test_admin_runtime_acl_hardening.py"
        real_sha256_file = goal_graph.continuation.sha256_file

        def changed_sha256(path: Path) -> str:
            if path == target:
                return "f" * 64
            return real_sha256_file(path)

        with mock.patch.object(
            goal_graph.continuation,
            "sha256_file",
            side_effect=changed_sha256,
        ):
            self.assertIsNone(
                goal_graph._current_security_database_compatibility_artifacts(
                    ROOT
                )
            )

    def test_current_security_database_added_source_rejects_third_digest(
        self,
    ) -> None:
        real_sha256_file = goal_graph.continuation.sha256_file
        for relative in (
            goal_graph.CURRENT_SECURITY_DATABASE_COMPATIBILITY_ADDED_SOURCES
        ):
            with self.subTest(relative=relative):
                target = ROOT / relative

                def changed_sha256(path: Path) -> str:
                    if path == target:
                        return "f" * 64
                    return real_sha256_file(path)

                with mock.patch.object(
                    goal_graph.continuation,
                    "sha256_file",
                    side_effect=changed_sha256,
                ):
                    self.assertIsNone(
                        goal_graph._current_security_database_compatibility_artifacts(
                            ROOT
                        )
                    )

    def test_current_security_database_rejects_unsealed_predecessor(
        self,
    ) -> None:
        real_strict_json_bytes = goal_graph.npc_recovery.strict_json_bytes

        def changed_predecessor(raw: bytes, label: str) -> dict[str, object]:
            document = real_strict_json_bytes(raw, label)
            if label != goal_graph.npc_recovery.V2_IMPLEMENTATION_REL.as_posix():
                return document
            document = copy.deepcopy(document)
            for row in document["execution_input_closure"]["files"]:
                if row["path"] == "backend/tests/test_fp046_postgres_integration.py":
                    row["sha256"] = "f" * 64
                    break
            return document

        with mock.patch.object(
            goal_graph.npc_recovery,
            "strict_json_bytes",
            side_effect=changed_predecessor,
        ):
            self.assertIsNone(
                goal_graph._current_security_database_compatibility_artifacts(
                    ROOT
                )
            )

    def test_standalone_goal_graph_passes_with_historical_witness(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                "-B",
                str(ROOT / "scripts/check_walksafe_goal_graph_v2_4.py"),
                "--skip-continuation",
            ],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

        self.assertEqual(completed.returncode, 0)
        self.assertIn(b"WalkSafe v2.4 Goal graph check: PASS", completed.stdout)
        self.assertEqual(completed.stderr, b"")

    def test_standalone_continuation_passes_without_pythonpath(self) -> None:
        environment = os.environ.copy()
        environment.pop("PYTHONPATH", None)
        completed = subprocess.run(
            [
                sys.executable,
                "-B",
                str(ROOT / "scripts/check_walksafe_project_continuation_v2_4.py"),
            ],
            cwd=ROOT,
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

        self.assertEqual(completed.returncode, 0)
        self.assertIn(
            b"WalkSafe v2.4 continuation check: PASS",
            completed.stdout,
        )
        self.assertEqual(completed.stderr, b"")

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


class WalkSafeGenericDependencyClosureTest(unittest.TestCase):
    @staticmethod
    def _fixture() -> dict[str, object]:
        epic, focus = "WS-GOAL-EPIC-03", "WS-GOAL-EPIC-04"
        fp, fp_next = "WS-GOAL-EPIC-03-FP-046-R001", "WS-GOAL-EPIC-03-FP-046-R002"
        npc, npc_next = "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R001", "WS-GOAL-EPIC-03-NPC-SINGLE-ADMIN-RECOVERY-R002"
        paths = {
            fp: "synthetic/fp-r001.md",
            fp_next: "synthetic/fp-r002.md",
            npc: "synthetic/npc-r001.md", npc_next: "synthetic/npc-r002.md",
        }
        hashes = {
            path: marker * 64
            for path, marker in zip(paths.values(), ("1", "2", "3", "4"))
        }
        source = {
            "role": "IMPLEMENTATION_GAP", "document_id": "WS-GAP-055-R029",
            "path": "synthetic/gap-r029.json", "file_sha256": "b" * 64,
        }
        common = {
            "goal_kind": "WORK_ITEM", "initial_status": "PLANNED",
            "parent_goal_id": epic, "work_item_type": "POLICY_GAP_WORK",
            "priority_rank": 1, "target_completion_level": "TARGET",
            "source_policy_ids": ["FP-046"], "gap_ids": ["GAP-055"],
            "canonical_input_roles": ["IMPLEMENTATION_GAP"], "output_subject_ids_by_role": {},
            "artifact_trigger_evidence_refs": [], "artifact_work_reason": "",
            "source_blocker_ids": [], "stop_policy": "NONE", "question_policy": "NONE",
        }

        def work_item(work_item_id: str, requires: list[str]) -> dict[str, object]:
            return {**common, "work_item_id": work_item_id, "start_requires": requires, "completion_requires": requires}

        nodes: dict[str, dict[str, object]] = {
            epic: {"goal_kind": "WORKSTREAM", "parent_goal_id": "WS-GOAL-MASTER"},
            fp: work_item("FP-046", []),
            npc: work_item("NPC-SINGLE-ADMIN-RECOVERY", [fp]),
        }
        for old, new, requires in ((fp, fp_next, []), (npc, npc_next, [fp_next])):
            nodes[new] = {
                **nodes[old], "start_requires": requires, "completion_requires": requires,
                "predecessor_goal_id": old, "predecessor_goal_content_sha256": hashes[paths[old]],
                "supersedes_goal_id": old, "supersedes_goal_content_sha256": hashes[paths[old]],
                "reopen_reason": "CANONICAL_INPUT_CHANGED",
                "materialized_from_role": source["role"],
                "materialized_from_document_id": source["document_id"],
                "materialized_from_path": source["path"],
                "materialized_from_sha256": source["file_sha256"],
            }
        before = {
            "IMPLEMENTATION_GAP": {
                **source, "path": "synthetic/gap-r028.json", "file_sha256": "a" * 64,
            }
        }
        after = {"IMPLEMENTATION_GAP": source}
        impacts = {
            fp: {"IMPLEMENTATION_GAP"},
            npc: {"DEPENDENCY_CLOSURE", "WORK_ITEM_DEPENDENCY_REVISION_REQUIRED"},
            epic: {"CHILD_AGGREGATE_INVALIDATED", "START_DEPENDENCY_INVALIDATED"},
        }
        completion_hashes = {epic: "e" * 64, fp: "6" * 64, npc: "7" * 64}
        active = {epic: ["EPIC03_COMPLETION"], fp: ["FP046_COMPLETION"], npc: ["NPC_COMPLETION"]}
        update = {
            "event_sha256": "c" * 64, "occurred_at": "2026-08-15T00:00:03+09:00",
            "subject_goal_id": epic, "focus_goal_id": focus,
            "canonical_binding_snapshot_after": after, "changed_binding_roles": ["IMPLEMENTATION_GAP"],
            "changed_subject_ids_by_role": {"IMPLEMENTATION_GAP": ["GAP-055"]},
            "impact_closure_goal_ids": sorted(impacts),
            "impact_disposition_by_goal": {
                fp: {"result": "REOPEN_REQUIRED"}, npc: {"result": "REOPEN_REQUIRED"},
                epic: {"result": "REOPEN_CONTAINER", "target_status": "PLANNED"},
            },
            "reopened_completion_event_sha256_by_goal": {epic: completion_hashes[epic]},
            "status_changes": {epic: "PLANNED"},
            "completion_evidence_by_goal_after": {fp: active[fp], npc: active[npc]},
            "archived_completion_evidence_by_goal_after": {epic: active[epic]},
        }
        return {
            "epic": epic, "focus": focus, "fp": fp, "fp_next": fp_next,
            "npc": npc, "npc_next": npc_next, "paths": paths, "hashes": hashes,
            "nodes": nodes, "before": before, "after": after, "impacts": impacts,
            "statuses": {fp: "COMPLETE_AT_TARGET", npc: "COMPLETE_AT_TARGET", epic: "COMPLETE_AT_TARGET"},
            "completion_hashes": completion_hashes,
            "completion_times": {completion_hashes[fp]: "2026-08-15T00:00:01+09:00",
                                 completion_hashes[npc]: "2026-08-15T00:00:02+09:00"},
            "active": active, "children": {epic: [fp, npc]}, "update": update,
        }

    @staticmethod
    def _update(fixture: dict[str, object], event: dict[str, object]):
        with (
            mock.patch.object(frozen_goal_graph, "validate_canonical_binding_snapshot", side_effect=lambda _root, value, **_kwargs: ([], value)),
            mock.patch.object(frozen_goal_graph, "canonical_changed_subject_ids_by_role", return_value=([], fixture["update"]["changed_subject_ids_by_role"])),
            mock.patch.object(frozen_goal_graph, "changed_binding_affected_goals", return_value={fixture["fp"]: {"IMPLEMENTATION_GAP"}}),
            mock.patch.object(frozen_goal_graph, "expand_affected_goal_dependency_closure", return_value=fixture["impacts"]),
        ):
            return continuation._validate_dependency_closure_update(
                ROOT, label="synthetic closure", graph=frozen_goal_graph, event=event,
                bindings_before=fixture["before"], nodes=fixture["nodes"], statuses=fixture["statuses"],
                completion_bindings_by_goal={}, latest_start_event_by_goal={}, completion_hashes=fixture["completion_hashes"],
                completion_times=fixture["completion_times"], latest_completion=fixture["active"], latest_archived_completion={},
            )

    @staticmethod
    def _successor(fixture: dict[str, object], old: str, new: str, active: dict[str, list[str]],
                   archived: dict[str, list[str]], pending: dict[str, dict[str, str]], seal: str) -> dict[str, object]:
        node = fixture["nodes"][new]
        active_after, archived_after = copy.deepcopy(active), copy.deepcopy(archived)
        archived_after[old] = active_after.pop(old)
        return {
            "event_sha256": seal, "subject_goal_id": old, "materialized_goal_id": new,
            "reopen_trigger": pending[old], "canonical_binding_snapshot_after": fixture["after"],
            **{field: node[field] for field in (
                "artifact_trigger_evidence_refs", "artifact_work_reason", "materialized_from_role",
                "materialized_from_document_id", "materialized_from_path",
            )},
            "materialized_from_sha256": node["materialized_from_sha256"],
            "materialized_goal_path": fixture["paths"][new],
            "materialized_goal_content_sha256": fixture["hashes"][fixture["paths"][new]],
            "predecessor_goal_id": node["predecessor_goal_id"],
            "predecessor_goal_content_sha256": node["predecessor_goal_content_sha256"],
            "supersedes_goal_id": old,
            "supersedes_goal_content_sha256": fixture["hashes"][fixture["paths"][old]],
            "evidence_refs": [], "completion_evidence_by_goal_after": active_after,
            "archived_completion_evidence_by_goal_after": archived_after,
        }

    @staticmethod
    def _successor_errors(fixture: dict[str, object], event: dict[str, object], before: dict[str, str],
                          pending: dict[str, dict[str, str]], rewritten: dict[str, str], active: object,
                          archived: object, label: str) -> list[str]:
        return continuation._validate_dependency_closure_successor(
            ROOT, label=label, graph=frozen_goal_graph, event=event, before=before,
            nodes=fixture["nodes"], goal_paths=fixture["paths"], bindings=fixture["after"],
            pending=pending, rewritten_successors=rewritten, latest_completion=active,
            latest_archived_completion=archived,
        )

    def test_replays_generic_closure_then_deferred_parent_projection(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        self.assertEqual(continuation.validate_transition_replay(
            ROOT, checkpoint, continuation.load_json(V23_ARCHIVE), V24_MANIFEST,
            expected_prepared_sha256=continuation.EXPECTED_V24_PREPARED_EVENT_SHA256,
            expected_authorization_sha256=continuation.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256,
        ), [])
        history = checkpoint["goal_execution"]["transition_history"]
        dispatch = copy.deepcopy(history[-1])
        dispatch.update(
            sequence=len(history) + 1, event_id="WS-SYNTHETIC-CLOSURE-077", event_type="CANONICAL_BINDINGS_UPDATED",
            previous_event_sha256=history[-1]["event_sha256"], subject_goal_id="WS-GOAL-EPIC-03",
            produced_by_goal_id=None, produced_binding_roles=[],
            producer_completion_receipt_binding=None,
            producer_output_subject_ids_by_role={},
            canonical_binding_snapshot_after=continuation.canonical_binding_snapshot(checkpoint),
            changed_binding_roles=[], changed_subject_ids_by_role={}, impact_closure_goal_ids=[],
            impact_disposition_by_goal={"WS-GOAL-EPIC-03": {"result": "REOPEN_CONTAINER", "target_status": "PLANNED"}},
            reopened_completion_event_sha256_by_goal={"WS-GOAL-EPIC-03": "e" * 64}, status_changes={"WS-GOAL-EPIC-03": "PLANNED"},
            from_status="READY", to_status="PLANNED",
        )
        dispatch["event_sha256"] = continuation.event_sha256(dispatch)
        history.append(dispatch)
        errors = continuation.validate_transition_replay(
            ROOT, checkpoint, continuation.load_json(V23_ARCHIVE), V24_MANIFEST
        )
        self.assertIn("canonical binding update is a no-op", "\n".join(errors))
        self.assertFalse([error for error in errors if error.startswith("generic order:")])
        fixture = self._fixture()
        errors, changes, pending, reopened = self._update(fixture, copy.deepcopy(fixture["update"]))
        self.assertEqual((errors, changes, set(pending)), ([], {fixture["epic"]: "PLANNED"}, {fixture["fp"], fixture["npc"]}))
        active = copy.deepcopy(fixture["update"]["completion_evidence_by_goal_after"])
        archived = copy.deepcopy(fixture["update"]["archived_completion_evidence_by_goal_after"])

        first = self._successor(fixture, fixture["fp"], fixture["fp_next"], active, archived, pending, "f" * 64)
        with (
            mock.patch.object(continuation, "resolve_repo_file", side_effect=lambda _root, path: Path("/synthetic") / path if path else None),
            mock.patch.object(continuation, "sha256_file", side_effect=lambda path: fixture["hashes"][path.as_posix().removeprefix("/synthetic/")]),
        ):
            self.assertEqual(self._successor_errors(
                fixture, first, {**fixture["statuses"], fixture["epic"]: "PLANNED"},
                pending, {}, active, archived, "first",
            ), [])
            active, archived = first["completion_evidence_by_goal_after"], first["archived_completion_evidence_by_goal_after"]
            pending.pop(fixture["fp"])
            second = self._successor(fixture, fixture["npc"], fixture["npc_next"], active, archived, pending, "d" * 64)
            self.assertEqual(self._successor_errors(
                fixture, second, {**fixture["statuses"], fixture["epic"]: "PLANNED", fixture["fp"]: "SUPERSEDED", fixture["fp_next"]: "PLANNED"},
                pending, {fixture["fp"]: fixture["fp_next"]}, active, archived, "second",
            ), [])
            successors = {fixture["fp_next"]: first["event_sha256"], fixture["npc_next"]: second["event_sha256"]}
            def record(goal_id: str, seal: str) -> dict[str, object]:
                node = fixture["nodes"][goal_id]
                return {
                    **{field: node[field] for field in (
                        "artifact_trigger_evidence_refs", "artifact_work_reason", "initial_status",
                        "work_item_type", "parent_goal_id", "materialized_from_role",
                        "materialized_from_path", "materialized_from_document_id",
                        "materialized_from_sha256", "predecessor_goal_id",
                        "predecessor_goal_content_sha256", "supersedes_goal_id",
                        "supersedes_goal_content_sha256",
                    )},
                    "goal_id": goal_id, "path": fixture["paths"][goal_id], "sha256": fixture["hashes"][fixture["paths"][goal_id]],
                    "goal_kind": "WORK_ITEM", "materialized_event_sha256": seal,
                }
            projection = {
                "subject_goal_id": fixture["epic"],
                "readiness_basis": {
                    "mode": "CANONICAL_DEPENDENCY_CLOSURE_REOPEN",
                    "canonical_update_event_sha256": fixture["update"]["event_sha256"],
                    "successor_event_sha256_by_goal": successors,
                    "archived_completion_event_sha256": fixture["completion_hashes"][fixture["epic"]],
                },
                "dynamic_goal_inventory_after": {goal: record(goal, seal) for goal, seal in successors.items()},
                "materialized_child_goal_ids_by_parent_after": {
                    fixture["epic"]: sorted(fixture["children"][fixture["epic"]] + list(successors))
                },
            }
            def validate_projection() -> list[str]:
                return continuation._validate_dependency_closure_inventory_projection(
                    ROOT, label="projection", event=projection, nodes=fixture["nodes"],
                    goal_paths=fixture["paths"], latest_inventory={}, latest_children=fixture["children"],
                    successors=successors, reopened_closure=reopened,
                )
            self.assertEqual(validate_projection(), [])
            projection["materialized_child_goal_ids_by_parent_after"][fixture["epic"]].remove(fixture["fp_next"])
            self.assertTrue(validate_projection())
            fixture["children"][fixture["epic"]].append(fixture["fp_next"])
            self.assertTrue(validate_projection())
            fixture["children"][fixture["epic"]] = [[]]
            self.assertTrue(validate_projection())
            fixture["nodes"][fixture["fp_next"]]["parent_goal_id"] = []
            self.assertTrue(validate_projection())

    def test_seq75_accepts_legacy_seq71_child_order_and_sorts_successors(self) -> None:
        fixture = self._fixture()
        _, _, _pending, reopened = self._update(
            fixture, copy.deepcopy(fixture["update"])
        )
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        latest_inventory = copy.deepcopy(state["dynamic_goal_inventory"])
        latest_children = copy.deepcopy(
            state["materialized_child_goal_ids_by_parent"]
        )
        successors = {
            fixture["fp_next"]: "f" * 64,
            fixture["npc_next"]: "d" * 64,
        }
        for goal_id in successors:
            latest_inventory.pop(goal_id)
        legacy_members = list(
            reversed(
                [
                    goal_id
                    for goal_id in latest_children[fixture["epic"]]
                    if goal_id not in successors
                ]
            )
        )
        latest_children[fixture["epic"]] = legacy_members
        self.assertNotEqual(legacy_members, sorted(legacy_members))

        def record(goal_id: str, seal: str) -> dict[str, object]:
            node = fixture["nodes"][goal_id]
            return {
                **{
                    field: node[field]
                    for field in (
                        "artifact_trigger_evidence_refs",
                        "artifact_work_reason",
                        "initial_status",
                        "work_item_type",
                        "parent_goal_id",
                        "materialized_from_role",
                        "materialized_from_path",
                        "materialized_from_document_id",
                        "materialized_from_sha256",
                        "predecessor_goal_id",
                        "predecessor_goal_content_sha256",
                        "supersedes_goal_id",
                        "supersedes_goal_content_sha256",
                    )
                },
                "goal_id": goal_id,
                "path": fixture["paths"][goal_id],
                "sha256": fixture["hashes"][fixture["paths"][goal_id]],
                "goal_kind": "WORK_ITEM",
                "materialized_event_sha256": seal,
            }

        projected_inventory = {
            **latest_inventory,
            **{
                goal_id: record(goal_id, seal)
                for goal_id, seal in successors.items()
            },
        }
        projected_children = copy.deepcopy(latest_children)
        projected_children[fixture["epic"]] = sorted(
            set(legacy_members) | set(successors)
        )
        projection = {
            "subject_goal_id": fixture["epic"],
            "readiness_basis": {
                "mode": "CANONICAL_DEPENDENCY_CLOSURE_REOPEN",
                "canonical_update_event_sha256": fixture["update"]["event_sha256"],
                "successor_event_sha256_by_goal": dict(sorted(successors.items())),
                "archived_completion_event_sha256": fixture["completion_hashes"][fixture["epic"]],
            },
            "dynamic_goal_inventory_after": projected_inventory,
            "materialized_child_goal_ids_by_parent_after": projected_children,
        }

        def validate_projection(
            children: dict[str, object], event: dict[str, object] = projection
        ) -> list[str]:
            return continuation._validate_dependency_closure_inventory_projection(
                ROOT,
                label="seq75 projection",
                event=event,
                nodes=fixture["nodes"],
                goal_paths=fixture["paths"],
                latest_inventory=latest_inventory,
                latest_children=children,
                successors=successors,
                reopened_closure=reopened,
            )

        with (
            mock.patch.object(
                continuation,
                "resolve_repo_file",
                side_effect=lambda _root, path: (
                    Path("/synthetic") / path if path else None
                ),
            ),
            mock.patch.object(
                continuation,
                "sha256_file",
                side_effect=lambda path: fixture["hashes"][
                    path.as_posix().removeprefix("/synthetic/")
                ],
            ),
        ):
            self.assertEqual(validate_projection(latest_children), [])
            unsorted_projection = copy.deepcopy(projection)
            unsorted_projection[
                "materialized_child_goal_ids_by_parent_after"
            ][fixture["epic"]] = legacy_members + list(successors)
            self.assertTrue(
                validate_projection(latest_children, unsorted_projection)
            )
            for members in (
                legacy_members + [legacy_members[0]],
                legacy_members + [42],
            ):
                malformed_children = copy.deepcopy(latest_children)
                malformed_children[fixture["epic"]] = members
                self.assertTrue(validate_projection(malformed_children))

    def test_rejects_scope_order_trigger_archive_and_inventory_drift(self) -> None:
        fixture = self._fixture()
        update = fixture["update"]
        for mutate in (
            lambda event: event.pop("changed_subject_ids_by_role"),
            lambda event: event.update({"subject_goal_id": fixture["focus"]}),
            lambda event: event.update({"subject_goal_id": []}),
            lambda event: event.update({"status_changes": {fixture["epic"]: "READY"}}),
            lambda event: event.update({"status_changes": {fixture["fp"]: "PLANNED"}}),
        ):
            event = copy.deepcopy(update)
            mutate(event)
            self.assertTrue(self._update(fixture, event)[0])
        _, _, pending, _ = self._update(fixture, copy.deepcopy(update))
        active = copy.deepcopy(update["completion_evidence_by_goal_after"])
        archived = copy.deepcopy(update["archived_completion_evidence_by_goal_after"])
        reverse = self._successor(fixture, fixture["npc"], fixture["npc_next"], active, archived, pending, "d" * 64)
        source = self._successor(fixture, fixture["fp"], fixture["fp_next"], active, archived, pending, "f" * 64)
        source["materialized_from_path"] = "docs/control/audits/r028.json"
        source["archived_completion_evidence_by_goal_after"] = {}
        source["dynamic_goal_inventory_after"] = {}
        top_level_trigger = self._successor(
            fixture, fixture["fp"], fixture["fp_next"], active, archived, pending, "f" * 64
        )
        top_level_trigger["target_completion_event_sha256"] = "0" * 64
        malformed_archive = self._successor(
            fixture, fixture["fp"], fixture["fp_next"], active, archived, pending, "f" * 64
        )
        with (
            mock.patch.object(continuation, "resolve_repo_file", side_effect=lambda _root, path: Path("/synthetic") / path if path else None),
            mock.patch.object(continuation, "sha256_file", side_effect=lambda path: fixture["hashes"][path.as_posix().removeprefix("/synthetic/")]),
        ):
            for event, prior_archive in (
                (reverse, archived), (source, archived),
                (top_level_trigger, archived), (malformed_archive, []),
                (malformed_archive, {fixture["fp"]: []}),
            ):
                self.assertTrue(self._successor_errors(
                    fixture, event, {**fixture["statuses"], fixture["epic"]: "PLANNED"},
                    pending, {}, active, prior_archive, "tamper",
                ))
        ready = {
            "subject_goal_id": fixture["fp_next"],
            "reopened_container_ready_event_sha256": "9" * 64,
        }
        self.assertEqual(
            continuation._validate_reopened_successor_ready(
                label="ready", event=ready, before={fixture["epic"]: "READY"},
                nodes=fixture["nodes"], container_ready_events={fixture["fp_next"]: "9" * 64},
            ),
            [],
        )
        for before, event in (({fixture["epic"]: "PLANNED"}, ready), ({fixture["epic"]: "READY"}, {**ready, "reopened_container_ready_event_sha256": "0" * 64})):
            self.assertTrue(continuation._validate_reopened_successor_ready(
                label="ready", event=event, before=before, nodes=fixture["nodes"],
                container_ready_events={fixture["fp_next"]: "9" * 64},
            ))
        history = WalkSafeProjectContinuationV24Test._generic_history_fixture()
        history[3]["produced_by_goal_id"] = []
        WalkSafeProjectContinuationV24Test._reseal_history(history)
        self.assertTrue(continuation.validate_generic_event_order(history))

    def test_r028_to_canonical_r029_only_removes_exact_provenance_star(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (
                *r029_candidate.R028_INPUT_PATHS,
                *r029_candidate.CURRENT_SOURCE_PATHS,
            ):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            outputs = r029_bridge.build_outputs(root)
            for relative, text in outputs.items():
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(text, encoding="utf-8")

            before_path = root / r029_candidate.R028_BACKLOG_JSON_REL
            after_path = root / r029_bridge.CANONICAL_BACKLOG_JSON_REL
            before_raw = before_path.read_bytes()
            after_raw = after_path.read_bytes()
            before_payload = json.loads(before_raw)
            after_payload = json.loads(after_raw)

            def binding(
                role: str, document_id: str, relative: Path
            ) -> dict[str, str]:
                return {
                    "role": role,
                    "document_id": document_id,
                    "path": relative.as_posix(),
                    "file_sha256": continuation.sha256_file(root / relative),
                }

            def upgraded(
                before_binding: dict[str, str],
                after_binding: dict[str, str],
                subjects: dict[str, list[str]],
            ) -> list[str]:
                return continuation._legacy_backlog_upgrade_subjects(
                    root,
                    frozen_goal_graph,
                    {"IMPLEMENTATION_BACKLOG": before_binding},
                    {"IMPLEMENTATION_BACKLOG": after_binding},
                    subjects,
                )["IMPLEMENTATION_BACKLOG"]

            base_before = binding(
                "IMPLEMENTATION_BACKLOG",
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
                r029_candidate.R028_BACKLOG_JSON_REL,
            )
            base_after = binding(
                "IMPLEMENTATION_BACKLOG",
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
                r029_bridge.CANONICAL_BACKLOG_JSON_REL,
            )
            generic_errors, raw_subjects = (
                frozen_goal_graph.canonical_changed_subject_ids_by_role(
                    root,
                    changed_roles=[
                        "IMPLEMENTATION_GAP",
                        "IMPLEMENTATION_BACKLOG",
                    ],
                    bindings_before={
                        "IMPLEMENTATION_GAP": binding(
                            "IMPLEMENTATION_GAP",
                            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028",
                            r029_candidate.R028_GAP_JSON_REL,
                        ),
                        "IMPLEMENTATION_BACKLOG": base_before,
                    },
                    bindings_after={
                        "IMPLEMENTATION_GAP": binding(
                            "IMPLEMENTATION_GAP",
                            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260815-029",
                            r029_bridge.CANONICAL_GAP_JSON_REL,
                        ),
                        "IMPLEMENTATION_BACKLOG": base_after,
                    },
                )
            )
            self.assertEqual(generic_errors, [])
            self.assertEqual(
                raw_subjects["IMPLEMENTATION_BACKLOG"], ["*", "FP-046"]
            )
            self.assertEqual(base_before, continuation.R008_R028_BACKLOG_BINDING)
            self.assertEqual(
                base_after,
                continuation.R008_R029_CANONICAL_BACKLOG_BINDING,
            )
            self.assertEqual(
                upgraded(base_before, base_after, raw_subjects), ["FP-046"]
            )

            candidate_outputs = r029_candidate.build_outputs(root)
            candidate_path = root / r029_candidate.R029_BACKLOG_JSON_REL
            candidate_path.parent.mkdir(parents=True, exist_ok=True)
            candidate_path.write_text(
                candidate_outputs[r029_candidate.R029_BACKLOG_JSON_REL],
                encoding="utf-8",
            )
            candidate_after = binding(
                "IMPLEMENTATION_BACKLOG",
                "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
                r029_candidate.R029_BACKLOG_JSON_REL,
            )
            self.assertEqual(candidate_path.read_bytes(), after_raw)
            self.assertIn(
                "*",
                upgraded(base_before, candidate_after, raw_subjects),
            )

            def write_case(
                changed_before: dict[str, object], changed_after: dict[str, object]
            ) -> tuple[dict[str, str], dict[str, str]]:
                if changed_before == before_payload:
                    before_path.write_bytes(before_raw)
                else:
                    before_path.write_text(
                        json.dumps(
                            changed_before,
                            ensure_ascii=False,
                            indent=2,
                            sort_keys=True,
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                if changed_after == after_payload:
                    after_path.write_bytes(after_raw)
                else:
                    after_path.write_text(
                        json.dumps(
                            changed_after,
                            ensure_ascii=False,
                            indent=2,
                            sort_keys=True,
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                return (
                    binding(
                        "IMPLEMENTATION_BACKLOG",
                        "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
                        r029_candidate.R028_BACKLOG_JSON_REL,
                    ),
                    binding(
                        "IMPLEMENTATION_BACKLOG",
                        "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260815-029",
                        r029_bridge.CANONICAL_BACKLOG_JSON_REL,
                    ),
                )

            tamper_cases = (
                (
                    "before canonical path",
                    lambda before, _after, before_binding, _after_binding: (
                        before_binding.update(path="docs/control/audits/other.json")
                    ),
                ),
                (
                    "before canonical hash",
                    lambda before, _after, before_binding, _after_binding: (
                        before_binding.update(file_sha256="0" * 64)
                    ),
                ),
                (
                    "candidate path",
                    lambda _before, _after, _before_binding, after_binding: (
                        after_binding.update(
                            path=r029_candidate.R029_BACKLOG_JSON_REL.as_posix(),
                            file_sha256=continuation.sha256_file(candidate_path),
                        )
                    ),
                ),
                (
                    "legacy provenance byte length",
                    lambda before, _after, _before_binding, _after_binding: (
                        before["source_predecessor"].update(byte_length=1)
                    ),
                ),
                (
                    "normalized provenance path",
                    lambda _before, after, _before_binding, _after_binding: (
                        after["source_predecessor"].update(path="docs/control/audits/other.json")
                    ),
                ),
                (
                    "normalized provenance hash",
                    lambda _before, after, _before_binding, _after_binding: (
                        after["source_predecessor"].update(file_sha256="0" * 64)
                    ),
                ),
                (
                    "normalized provenance flag",
                    lambda _before, after, _before_binding, _after_binding: (
                        after["source_predecessor"].update(preserved_unchanged=False)
                    ),
                ),
                (
                    "global value",
                    lambda _before, after, _before_binding, _after_binding: (
                        after.update(current_status_model="tampered")
                    ),
                ),
                (
                    "metadata residual",
                    lambda _before, after, _before_binding, _after_binding: (
                        after["metadata"].update(status="tampered")
                    ),
                ),
                (
                    "unknown residual",
                    lambda _before, after, _before_binding, _after_binding: (
                        after.update(unexpected_residual=True)
                    ),
                ),
            )
            for label, mutate in tamper_cases:
                changed_before = copy.deepcopy(before_payload)
                changed_after = copy.deepcopy(after_payload)
                before_binding, after_binding = write_case(
                    changed_before, changed_after
                )
                mutate(
                    changed_before,
                    changed_after,
                    before_binding,
                    after_binding,
                )
                if changed_before != before_payload or changed_after != after_payload:
                    before_binding, after_binding = write_case(
                        changed_before, changed_after
                    )
                self.assertIn(
                    "*",
                    upgraded(before_binding, after_binding, raw_subjects),
                    label,
                )


class WalkSafeR002Seq72BoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        assigned_at = "2026-08-23T06:00:00+09:00"
        cls.archive = continuation.load_json(V23_ARCHIVE)
        tracked = subprocess.run(
            ["git", "ls-files", "-z"],
            cwd=ROOT,
            check=True,
            capture_output=True,
        ).stdout
        paths = {
            value.decode("utf-8")
            for value in tracked.split(b"\0")
            if value
        }
        paths.update(
            relative.as_posix()
            for relative in r008_control_review.EVIDENCE_PATHS
        )
        source_checkpoint = continuation.load_json(CHECKPOINT)
        paths.update(
            source_checkpoint["working_tree_snapshot"]["managed_changed_paths"]
        )
        cls._temporary = tempfile.TemporaryDirectory(
            prefix="walksafe-continuation-seq72-76-"
        )
        cls.addClassCleanup(cls._temporary.cleanup)
        cls.root = Path(cls._temporary.name)
        for relative in paths:
            source = ROOT / relative
            if not source.is_file():
                continue
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        directories = {path.parent for path in cls.root.rglob("*")}
        for directory in sorted(
            directories, key=lambda path: len(path.parts), reverse=True
        ):
            source = ROOT / directory.relative_to(cls.root)
            if source.is_dir():
                os.chmod(directory, source.stat().st_mode & 0o777)
        os.symlink(ROOT / ".git", cls.root / ".git", target_is_directory=True)

        r011_binding, r011_raw = (
            continuation._load_fp046_r002_frozen_r011_review(cls.root)
        )
        r010_binding, r010_raw = (
            r008_review.load_frozen_control_successor_r010(cls.root)
        )
        r009_binding, r009_raw = (
            r008_review.load_frozen_control_successor_r009(cls.root)
        )

        r004_binding, r004_raw = (
            continuation._load_fp046_r002_frozen_r004_review(cls.root)
        )
        r003_binding, r003_raw = r008_review.load_frozen_transition_r003(
            cls.root
        )
        _r002_binding, r002_raw = r008_review.load_frozen_transition_r002(
            cls.root
        )
        _r001_binding, r001_raw = r008_review.load_frozen_transition_r001(
            cls.root
        )
        cls.checkpoint = continuation.load_json(
            cls.root / r008_review.CHECKPOINT_REL
        )
        cls.review_paths = tuple(
            sorted(
                {
                    *r001_raw,
                    *r002_raw,
                    *r003_raw,
                    *r004_raw,
                    *r009_raw,
                    *r010_raw,
                    *r011_raw,
                }
            )
        )
        cls.event_review_binding_by_field = {
            "predecessor_transition_review_binding": r003_binding,
            "transition_review_binding": r004_binding,
            "r009_control_review_binding": r009_binding,
            "r010_control_review_binding": r010_binding,
            "r011_control_review_binding": r011_binding,
        }
        cls.review_label_by_path = {
            **{
                path: "frozen R001 transition review"
                for path in r001_raw
            },
            **{
                path: "frozen R002 transition review"
                for path in r002_raw
            },
            **{
                path: "frozen R003 transition review"
                for path in r003_raw
            },
            **{
                path: "frozen R004 transition review"
                for path in r004_raw
            },
            **{
                path: "frozen R009 control review"
                for path in r009_raw
            },
            **{
                path: "frozen R010 control review"
                for path in r010_raw
            },
            **{
                path: "frozen R011 control review"
                for path in r011_raw
            },
        }
        cls.authorization_sha256 = cls.checkpoint["goal_execution"][
            "transition_history"
        ][1]["package_activation_authorization_binding"]["file_sha256"]
        seq72 = cls.checkpoint["goal_execution"]["transition_history"][71]
        cls.transaction = {
            "transition_review_subject_binding": copy.deepcopy(
                seq72["transition_review_subject_binding"]
            ),
            "source_bindings": [
                {
                    "path": relative.as_posix(),
                    "sha256": hashlib.sha256(raw).hexdigest(),
                    "byte_length": len(raw),
                }
                for relative, raw in {
                    **r001_raw,
                    **r002_raw,
                    **r003_raw,
                    **r004_raw,
                    **r009_raw,
                    **r010_raw,
                    **r011_raw,
                }.items()
            ],
        }

    @classmethod
    def _replay(cls, checkpoint: dict[str, object]) -> list[str]:
        return continuation.validate_transition_replay(
            cls.root,
            checkpoint,
            cls.archive,
            V24_MANIFEST.relative_to(ROOT),
            expected_authorization_sha256=cls.authorization_sha256,
        )

    @staticmethod
    def _reseal_from(checkpoint: dict[str, object], start: int) -> None:
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        previous = history[start - 1]["event_sha256"] if start else ""
        for event in history[start:]:
            event["previous_event_sha256"] = previous
            event["event_sha256"] = continuation.event_sha256(event)
            previous = event["event_sha256"]
        state["transition_history_anchor_sha256"] = previous

    @staticmethod
    def _append_exact_seq77_78_status_suffix(
        checkpoint: dict[str, object],
    ) -> None:
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        seq76 = history[-1]
        runtime = copy.deepcopy(seq76["runtime_after"])
        common = {
            "occurred_on": "2026-08-23",
            "previous_focus_goal_id": r008_review.FP046_R002,
            "previous_focus_content_sha256": seq76[
                "focus_goal_content_sha256"
            ],
            "focus_goal_id": r008_review.FP046_R002,
            "focus_goal_content_sha256": seq76[
                "focus_goal_content_sha256"
            ],
            "static_plan_manifest_sha256": seq76[
                "static_plan_manifest_sha256"
            ],
            "runtime_after": runtime,
            "blockers_after": copy.deepcopy(seq76["blockers_after"]),
            "blocker_resolution_ids_after": copy.deepcopy(
                seq76["blocker_resolution_ids_after"]
            ),
            "source_checkpoint_version": seq76[
                "source_checkpoint_version"
            ],
            "subject_goal_id": r008_review.FP046_R002,
        }
        if len(history) == 76:
            seq77 = {
                **copy.deepcopy(common),
                "sequence": 77,
                "event_id": continuation.FP046_R002_CONTROL_REANCHOR_EVENT_ID,
                "event_type": "GOAL_START_CONTROL_REANCHORED",
                "occurred_at": "2026-08-23T12:00:00+09:00",
                "previous_event_sha256": seq76["event_sha256"],
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
            }
            seq77["event_sha256"] = continuation.event_sha256(seq77)
            history.append(seq77)
        else:
            seq77 = history[76]
        seq78 = {
            **copy.deepcopy(common),
            "sequence": 78,
            "event_id": continuation.FP046_R002_CONTROL_CORRECTION_EVENT_ID,
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_at": "2026-08-23T12:00:01+09:00",
            "previous_event_sha256": seq77["event_sha256"],
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
        }
        seq78["event_sha256"] = continuation.event_sha256(seq78)
        seq79 = {
            **copy.deepcopy(common),
            "sequence": 79,
            "event_id": continuation.FP046_R002_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "occurred_at": "2026-08-23T12:00:02+09:00",
            "previous_event_sha256": seq78["event_sha256"],
            "from_status": "READY",
            "to_status": "IN_PROGRESS",
            "status_changes": {r008_review.FP046_R002: "IN_PROGRESS"},
        }
        seq79["event_sha256"] = continuation.event_sha256(seq79)
        history.extend((seq78, seq79))
        state["status_by_goal"][r008_review.FP046_R002] = "IN_PROGRESS"
        state["transition_history_anchor_sha256"] = seq79["event_sha256"]
        state["validation_cutoff_at"] = seq79["occurred_at"]

    @classmethod
    def _validate_boundary_with_live_checkpoint(
        cls,
        checkpoint: dict[str, object],
    ) -> list[str]:
        path = cls.root / r008_review.CHECKPOINT_REL
        original = path.read_bytes()
        try:
            path.write_bytes(r008_review.json_text(checkpoint).encode("utf-8"))
            return continuation.validate_fp046_npc_r002_seq72_boundary(
                cls.root,
                checkpoint,
            )
        finally:
            path.write_bytes(original)

    def test_actual_seq72_76_projection_passes_full_replay(self) -> None:
        self.assertEqual(self._replay(copy.deepcopy(self.checkpoint)), [])
        history = self.checkpoint["goal_execution"]["transition_history"]
        self.assertEqual(history[71]["to_status"], "PLANNED")
        self.assertEqual(history[74]["to_status"], "READY")

        seq72 = history[71]
        self.assertNotIn("r008_control_review_binding", seq72)
        self.assertEqual(
            {
                field: seq72[field]
                for field in self.event_review_binding_by_field
            },
            self.event_review_binding_by_field,
        )
        self.assertEqual(
            seq72["transition_review_subject_binding"],
            self.transaction["transition_review_subject_binding"],
        )

    def test_seq72_requires_twenty_one_review_files_in_all_closures(self) -> None:
        expected = {path.as_posix() for path in self.review_paths}
        source_by_path = {
            row["path"]: row for row in self.transaction["source_bindings"]
        }
        snapshot = self.checkpoint["working_tree_snapshot"]
        handoff = self.checkpoint["session_handoff"]

        self.assertTrue(expected.issubset(source_by_path))
        self.assertEqual(
            {path: source_by_path[path] for path in expected},
            {
                relative.as_posix(): r008_review._binding(
                    relative,
                    (self.root / relative).read_bytes(),
                )
                for relative in self.review_paths
            },
        )
        self.assertTrue(expected.issubset(snapshot["managed_changed_paths"]))
        self.assertTrue(expected.issubset(handoff["changed_files"]))

    def test_seq72_r004_core_preserves_frozen_and_control_authority(self) -> None:
        seq72 = self.checkpoint["goal_execution"]["transition_history"][71]

        for field in (
            "predecessor_transition_review_binding",
            "transition_review_binding",
            "r009_control_review_binding",
            "r010_control_review_binding",
            "r011_control_review_binding",
        ):
            self.assertEqual(
                seq72[field],
                self.event_review_binding_by_field[field],
            )
        self.assertEqual(
            seq72["transition_review_subject_binding"],
            self.transaction["transition_review_subject_binding"],
        )

    def test_seq72_requires_every_review_file_in_the_twenty_one_file_closure(
        self,
    ) -> None:
        for relative in self.review_paths:
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_bytes()
                try:
                    path.unlink()
                    errors = continuation.validate_fp046_npc_r002_seq72_boundary(
                        self.root, copy.deepcopy(self.checkpoint)
                    )
                finally:
                    path.write_bytes(original)
                self.assertIn(
                    f"seq72 {self.review_label_by_path[relative]} differs",
                    "\n".join(errors),
                )

    def test_seq72_rejects_each_review_file_byte_tamper(self) -> None:
        for relative in self.review_paths:
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_bytes()
                try:
                    path.write_bytes(original + b" ")
                    errors = continuation.validate_fp046_npc_r002_seq72_boundary(
                        self.root, copy.deepcopy(self.checkpoint)
                    )
                finally:
                    path.write_bytes(original)
                self.assertIn(
                    f"seq72 {self.review_label_by_path[relative]} differs",
                    "\n".join(errors),
                )

    def test_seq72_r029_gap_and_backlog_tamper_errors_are_specific(self) -> None:
        for role, expected in (
            ("IMPLEMENTATION_GAP", "canonical IMPLEMENTATION_GAP file differs"),
            (
                "IMPLEMENTATION_BACKLOG",
                "canonical IMPLEMENTATION_BACKLOG file differs",
            ),
        ):
            with self.subTest(role=role):
                relative = Path(
                    self.checkpoint["goal_execution"]["transition_history"][71][
                        "canonical_binding_snapshot_after"
                    ][role]["path"]
                )
                path = self.root / relative
                original = path.read_bytes()
                try:
                    path.write_bytes(b"{}\n")
                    errors = continuation.validate_fp046_npc_r002_seq72_boundary(
                        self.root, copy.deepcopy(self.checkpoint)
                    )
                finally:
                    path.write_bytes(original)
                self.assertIn(expected, "\n".join(errors))

    def test_seq72_rejects_ready_instead_of_two_stage_planned_reopen(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = checkpoint["goal_execution"]["transition_history"][71]
        goal_id = event["subject_goal_id"]
        event["status_changes"][goal_id] = "READY"
        event["impact_disposition_by_goal"][goal_id]["target_status"] = "READY"
        event["to_status"] = "READY"
        self._reseal_from(checkpoint, 71)
        errors = self._replay(checkpoint)
        self.assertIn(
            "canonical change impact disposition set differs", "\n".join(errors)
        )

    def test_full_replay_requires_seq75_parent_ready_transition(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        checkpoint["goal_execution"]["transition_history"][74][
            "to_status"
        ] = "PLANNED"
        self._reseal_from(checkpoint, 74)

        self.assertIn(
            "generic order: generic event 75 from/to boundary differs",
            "\n".join(self._replay(checkpoint)),
        )

    def test_seq72_review_bindings_reject_missing_path_sha_and_length_after_reseal(
        self,
    ) -> None:
        cases = (
            (
                "missing",
                "predecessor_transition_review_binding",
                "frozen R003 transition review",
            ),
            (
                "path",
                "transition_review_binding",
                "frozen R004 transition review",
            ),
            (
                "sha256",
                "r009_control_review_binding",
                "frozen R009 control review",
            ),
            (
                "byte_length",
                "r010_control_review_binding",
                "frozen R010 control review",
            ),
            (
                "sha256",
                "r011_control_review_binding",
                "frozen R011 control review",
            ),
        )
        for mutation, field, label in cases:
            with self.subTest(mutation=mutation, field=field):
                checkpoint = copy.deepcopy(self.checkpoint)
                event = checkpoint["goal_execution"]["transition_history"][71]
                if mutation == "missing":
                    event.pop(field)
                elif mutation == "path":
                    event[field]["assignment"]["path"] = (
                        r008_review.TRANSITION_R003_ASSIGNMENT_REL.as_posix()
                    )
                elif mutation == "sha256":
                    event[field]["review_result"]["sha256"] = "0" * 64
                else:
                    event[field]["independent_review"]["byte_length"] += 1
                self._reseal_from(checkpoint, 71)

                self.assertIn(
                    f"seq72 {label} byte binding differs",
                    "\n".join(self._replay(checkpoint)),
                )

    def test_seq72_review_subject_binding_rejects_missing_sha_and_length(
        self,
    ) -> None:
        for mutation in ("missing", "sha256", "byte_length"):
            with self.subTest(mutation=mutation):
                checkpoint = copy.deepcopy(self.checkpoint)
                event = checkpoint["goal_execution"]["transition_history"][71]
                binding = event["transition_review_subject_binding"]
                if mutation == "missing":
                    event.pop("transition_review_subject_binding")
                elif mutation == "sha256":
                    binding["sha256"] = "0" * 64
                else:
                    binding["byte_length"] += 1
                self._reseal_from(checkpoint, 71)

                self.assertIn(
                    "seq72 approval-neutral reviewed core differs",
                    "\n".join(self._replay(checkpoint)),
                )

    def test_seq72_rejects_superseded_r008_binding_after_reseal(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = checkpoint["goal_execution"]["transition_history"][71]
        event["r008_control_review_binding"] = copy.deepcopy(
            event["r009_control_review_binding"]
        )
        self._reseal_from(checkpoint, 71)

        self.assertIn(
            "seq72 superseded R008 review binding is present",
            "\n".join(self._replay(checkpoint)),
        )

    def test_seq72_rejects_unknown_review_binding_after_reseal(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = checkpoint["goal_execution"]["transition_history"][71]
        event["arbitrary_review_binding"] = copy.deepcopy(
            event["r011_control_review_binding"]
        )
        self._reseal_from(checkpoint, 71)

        self.assertIn(
            "seq72 review binding field inventory differs",
            "\n".join(self._replay(checkpoint)),
        )

    def test_seq72_requires_all_twenty_one_review_paths_after_snapshot_rehash(
        self,
    ) -> None:
        for relative in self.review_paths:
            with self.subTest(relative=relative):
                checkpoint = copy.deepcopy(self.checkpoint)
                snapshot = checkpoint["working_tree_snapshot"]
                removed = relative.as_posix()
                snapshot["managed_changed_paths"].remove(removed)
                snapshot["managed_changed_path_count"] -= 1
                path_hash, content_hash = r008_transaction._overlay_hashes(
                    self.root,
                    snapshot["managed_changed_paths"],
                    {},
                )
                snapshot["path_set_sha256"] = path_hash
                snapshot["content_set_sha256"] = content_hash
                handoff = checkpoint["session_handoff"]
                handoff["changed_files"] = copy.deepcopy(
                    snapshot["managed_changed_paths"]
                )
                handoff["source_commit_or_snapshot"].update(
                    {
                        "file_count": snapshot["managed_changed_path_count"],
                        "path_set_sha256": path_hash,
                        "content_set_sha256": content_hash,
                    }
                )

                self.assertIn(
                    "seq72 twenty-one-file review managed closure differs",
                    "\n".join(self._replay(checkpoint)),
                )

    def test_seq72_approval_neutral_core_rejects_resealed_event_tamper(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = checkpoint["goal_execution"]["transition_history"][75]
        event["occurred_at"] = "2099-01-01T00:00:00+09:00"
        self._reseal_from(checkpoint, 75)

        self.assertIn(
            "seq72 approval-neutral reviewed core differs",
            "\n".join(self._replay(checkpoint)),
        )

    def test_seq72_reviewed_core_accepts_exact_seq77_78_status_suffix(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        self._append_exact_seq77_78_status_suffix(checkpoint)

        self.assertEqual(
            self._validate_boundary_with_live_checkpoint(checkpoint),
            [],
        )

    def test_seq72_reviewed_core_rederives_seq76_queue_after_later_change(
        self,
    ) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        self._append_exact_seq77_78_status_suffix(checkpoint)
        state = checkpoint["goal_execution"]
        state["artifact_work_queue"] = {"post_seq76_projection": True}
        state["completion_boundary"] = {"post_seq76_projection": True}
        seq78 = state["transition_history"][77]
        seq78["runtime_after"]["artifact_work_queue_sha256"] = (
            continuation.canonical_json_sha256(state["artifact_work_queue"])
        )
        seq78["runtime_after"]["completion_boundary_sha256"] = (
            continuation.canonical_json_sha256(state["completion_boundary"])
        )
        self._reseal_from(checkpoint, 77)

        self.assertEqual(
            self._validate_boundary_with_live_checkpoint(checkpoint),
            [],
        )

    def test_seq72_reviewed_core_rewinds_later_materialized_status(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        self._append_exact_seq77_78_status_suffix(checkpoint)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        goal_id = "WS-GOAL-EPIC-03-POST-SEQ76-TEST-R001"
        seq79 = {
            "sequence": 80,
            "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-TEST-001",
            "event_type": "GOAL_MATERIALIZED",
            "occurred_on": "2026-08-23",
            "occurred_at": "2026-08-23T12:00:02+09:00",
            "previous_event_sha256": history[-1]["event_sha256"],
            "materialized_goal_id": goal_id,
            "from_status": None,
            "to_status": "PLANNED",
            "status_changes": {goal_id: "PLANNED"},
        }
        seq79["event_sha256"] = continuation.event_sha256(seq79)
        history.append(seq79)
        state["status_by_goal"][goal_id] = "PLANNED"
        state["dynamic_goal_inventory"][goal_id] = {
            "goal_id": goal_id,
            "path": "post-seq76-test-goal.md",
        }
        state["transition_history_anchor_sha256"] = seq79["event_sha256"]
        state["validation_cutoff_at"] = seq79["occurred_at"]

        self.assertEqual(
            self._validate_boundary_with_live_checkpoint(checkpoint),
            [],
        )

    def test_seq72_reviewed_core_rewinds_later_successor_statuses(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        self._append_exact_seq77_78_status_suffix(checkpoint)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        successor_id = "WS-GOAL-EPIC-03-POST-SEQ76-SUCCESSOR-TEST-R001"
        seq79 = {
            "sequence": 80,
            "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-SUPERSEDED-TEST-001",
            "event_type": "GOAL_SUPERSEDED",
            "occurred_on": "2026-08-23",
            "occurred_at": "2026-08-23T12:00:02+09:00",
            "previous_event_sha256": history[-1]["event_sha256"],
            "subject_goal_id": r008_review.NPC_R002,
            "materialized_goal_id": successor_id,
            "from_status": "PLANNED",
            "to_status": "SUPERSEDED",
            "status_changes": {
                r008_review.NPC_R002: "SUPERSEDED",
                successor_id: "PLANNED",
            },
        }
        seq79["event_sha256"] = continuation.event_sha256(seq79)
        history.append(seq79)
        state["status_by_goal"][r008_review.NPC_R002] = "SUPERSEDED"
        state["status_by_goal"][successor_id] = "PLANNED"
        state["dynamic_goal_inventory"][successor_id] = {
            "goal_id": successor_id,
            "path": "post-seq76-successor-test-goal.md",
        }
        state["transition_history_anchor_sha256"] = seq79["event_sha256"]
        state["validation_cutoff_at"] = seq79["occurred_at"]

        self.assertEqual(
            self._validate_boundary_with_live_checkpoint(checkpoint),
            [],
        )

    def test_seq72_reviewed_core_rejects_tampered_seq78_from_status(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        self._append_exact_seq77_78_status_suffix(checkpoint)
        seq78 = checkpoint["goal_execution"]["transition_history"][77]
        seq78["from_status"] = "PLANNED"
        self._reseal_from(checkpoint, 77)

        self.assertIn(
            "seq72 approval-neutral reviewed core differs",
            "\n".join(self._validate_boundary_with_live_checkpoint(checkpoint)),
        )

    def test_seq72_reviewed_core_rejects_ambiguous_post_seq76_changes(
        self,
    ) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        self._append_exact_seq77_78_status_suffix(checkpoint)
        state = checkpoint["goal_execution"]
        seq79 = state["transition_history"][78]
        seq79["status_changes"][r008_review.NPC_R002] = "READY"
        state["status_by_goal"][r008_review.NPC_R002] = "READY"
        self._reseal_from(checkpoint, 78)

        self.assertIn(
            "post-seq76 status transition is ambiguous: 79",
            "\n".join(self._validate_boundary_with_live_checkpoint(checkpoint)),
        )

    def test_seq72_rejects_forged_missing_or_empty_producer_control(self) -> None:
        for value in ("missing", "", continuation.FP022_GOAL_ID):
            with self.subTest(value=value):
                checkpoint = copy.deepcopy(self.checkpoint)
                event = checkpoint["goal_execution"]["transition_history"][71]
                if value == "missing":
                    event.pop("produced_by_goal_id")
                else:
                    event["produced_by_goal_id"] = value
                self._reseal_from(checkpoint, 71)
                self.assertIn(
                    "dependency closure producer control differs",
                    "\n".join(self._replay(checkpoint)),
                )

    def test_seq72_boundary_rejects_event_type_agnostic_state_projection(self) -> None:
        cases = {
            "canonical": "event type cannot change canonical bindings",
            "completion": "event type cannot project completion evidence",
            "archived": "event type cannot project archived completion evidence",
            "inventory": "event type cannot project Goal inventory",
            "blockers": "event type cannot change blocker map",
        }
        for mutation, expected in cases.items():
            with self.subTest(mutation=mutation):
                checkpoint = copy.deepcopy(self.checkpoint)
                state = checkpoint["goal_execution"]
                event = state["transition_history"][-1]
                if mutation == "canonical":
                    candidate = continuation.canonical_binding_snapshot(checkpoint)
                    candidate["SMUGGLED"] = {
                        "role": "SMUGGLED",
                        "document_id": "SMUGGLED",
                        "path": "missing-smuggled.json",
                        "file_sha256": "0" * 64,
                    }
                    event["canonical_binding_snapshot_after"] = candidate
                elif mutation == "completion":
                    value = copy.deepcopy(state["completion_evidence_by_goal"])
                    value["SMUGGLED"] = []
                    event["completion_evidence_by_goal_after"] = value
                    state["completion_evidence_by_goal"] = value
                elif mutation == "archived":
                    value = copy.deepcopy(
                        state["archived_completion_evidence_by_goal"]
                    )
                    value["SMUGGLED"] = []
                    event["archived_completion_evidence_by_goal_after"] = value
                    state["archived_completion_evidence_by_goal"] = value
                elif mutation == "inventory":
                    inventory = copy.deepcopy(state["dynamic_goal_inventory"])
                    inventory["SMUGGLED"] = {"goal_id": "SMUGGLED"}
                    event["dynamic_goal_inventory_after"] = inventory
                    event["materialized_child_goal_ids_by_parent_after"] = (
                        copy.deepcopy(state["materialized_child_goal_ids_by_parent"])
                    )
                    state["dynamic_goal_inventory"] = inventory
                else:
                    blockers = {"SMUGGLED": []}
                    event["blockers_after"] = blockers
                    state["blockers_by_goal"] = blockers
                self._reseal_from(checkpoint, len(state["transition_history"]) - 1)
                self.assertIn(expected, "\n".join(self._replay(checkpoint)))

    def test_full_replay_rejects_unknown_top_level_after_projection(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = checkpoint["goal_execution"]["transition_history"][75]
        event["arbitrary_after"] = {"smuggled": True}
        self._reseal_from(checkpoint, 75)

        self.assertIn(
            "v2.4 event 76 top-level after projection fields are not allowed: arbitrary_after",
            "\n".join(self._replay(checkpoint)),
        )

    def test_full_replay_rejects_runtime_after_extra_field(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        event = checkpoint["goal_execution"]["transition_history"][75]
        event["runtime_after"]["smuggled"] = True
        self._reseal_from(checkpoint, 75)

        self.assertIn(
            "v2.4 event 76 runtime_after field set differs",
            "\n".join(self._replay(checkpoint)),
        )

    def test_successor_predecessor_hash_is_bound_to_live_goal_bytes(self) -> None:
        checkpoint = copy.deepcopy(self.checkpoint)
        successor = checkpoint["goal_execution"]["transition_history"][72]
        paths = continuation._goal_path_by_id(self.archive, checkpoint)
        predecessor = self.root / paths[successor["predecessor_goal_id"]]
        original = predecessor.read_bytes()
        try:
            predecessor.write_bytes(original + b"\n")
            errors = self._replay(checkpoint)
        finally:
            predecessor.write_bytes(original)
        self.assertIn(
            "successor predecessor live binding differs", "\n".join(errors)
        )


class WalkSafeFp046R002Seq77Seq78BoundaryTest(unittest.TestCase):
    @staticmethod
    def _reviewed_seq78_fixture():
        review, reanchor, gate, _started = (
            continuation._fp046_r002_seq77_78_modules()
        )
        source_raw, source = reanchor.load_frozen_source_checkpoint(ROOT)
        if (
            len(source_raw) != reanchor.SOURCE_BYTE_COUNT
            or hashlib.sha256(source_raw).hexdigest() != reanchor.SOURCE_SHA256
        ):
            raise AssertionError("frozen seq76 fixture authority differs")
        final = {
            Path(path): "a" * 64
            for path in source["working_tree_snapshot"][
                "managed_changed_paths"
            ]
        }
        final.update({path: "b" * 64 for path in reanchor.REQUIRED_CONTROL_PATHS})
        successor = reanchor._load_r002_contract(ROOT)[1]
        authorization = reanchor.authorization_binding(ROOT)
        runner = reanchor.start_gate_runner_binding(ROOT)
        review_binding = {
            "assignment": {"path": "review-assignment.json"},
            "review_result": {"path": "review-result.json"},
            "independent_review": {"path": "independent-review.json"},
        }
        checkpoint, seq77 = reanchor.project(
            source,
            final,
            event_occurred_at="2026-08-23T12:00:00+09:00",
            successor_contract=successor,
            authorization=authorization,
            runner_binding=runner,
            transition_review=review_binding,
        )
        seq78 = {
            "sequence": 78,
            "event_id": continuation.FP046_R002_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "occurred_on": "2026-08-23",
            "occurred_at": "2026-08-23T12:00:04+09:00",
            "previous_focus_goal_id": continuation.FP046_R002_GOAL_ID,
            "previous_focus_content_sha256": continuation.FP046_R002_GOAL_SHA256,
            "focus_goal_id": continuation.FP046_R002_GOAL_ID,
            "focus_goal_content_sha256": continuation.FP046_R002_GOAL_SHA256,
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
            "from_status": "READY",
            "to_status": "IN_PROGRESS",
            "static_plan_manifest_sha256": seq77[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {
                continuation.FP046_R002_GOAL_ID: "IN_PROGRESS"
            },
            "runtime_after": copy.deepcopy(seq77["runtime_after"]),
            "repository_snapshot_before": {"sealed": True},
            "implementation_start_gate_binding": {
                "document_id": "WS-FP046-R002-START-GATE-RECEIPT-TEST",
                "path": "private-test-receipt.json",
                "file_sha256": "c" * 64,
            },
            "blockers_after": copy.deepcopy(seq77["blockers_after"]),
            "blocker_resolution_ids_after": copy.deepcopy(
                seq77["blocker_resolution_ids_after"]
            ),
            "source_checkpoint_version": checkpoint["schema_version"],
            "evidence_refs": [],
            "previous_event_sha256": seq77["event_sha256"],
        }
        seq78["event_sha256"] = continuation.event_sha256(seq78)
        checkpoint["goal_execution"]["transition_history"].append(seq78)

        context = mock.Mock(
            frozen_r011_cohort=[None] * 23,
            current_control_cohort=[None] * 31,
            control_code_successors=[None] * 7,
            added_control_code_bindings=[None] * 8,
        )
        reviewed = mock.Mock()
        reviewed.validate_post_review.return_value = context
        reviewed.transition_review_binding.return_value = review_binding
        started = mock.Mock(EVENT_FIELDS=continuation.V24_FIRST_START_EVENT_FIELDS)
        started.validate_history_suffix.return_value = []
        return checkpoint, reviewed, reanchor, gate, started, review_binding

    @staticmethod
    def _validate_reviewed_fixture(
        checkpoint, reviewed, reanchor, gate, started, review_binding
    ) -> list[str]:
        with (
            mock.patch.object(
                continuation,
                "_fp046_r002_seq77_78_modules",
                return_value=(reviewed, reanchor, gate, started),
            ),
            mock.patch.object(
                continuation,
                "_validate_fp046_r002_frozen_r011",
                return_value=[],
            ),
            mock.patch.object(
                reanchor,
                "transition_review_binding",
                return_value=review_binding,
            ),
            mock.patch.object(
                reanchor,
                "validate_history_suffix",
                return_value=[],
            ),
        ):
            return continuation.validate_fp046_r002_seq77_78_boundary(
                ROOT,
                checkpoint,
            )

    @staticmethod
    def _actual_private_suffix_fixture(root: Path) -> dict[str, object]:
        checkpoint, reviewed, reanchor, gate, _started, review_binding = (
            WalkSafeFp046R002Seq77Seq78BoundaryTest._reviewed_seq78_fixture()
        )
        _review, _reanchor, _gate, started = (
            continuation._fp046_r002_seq77_78_modules()
        )
        checkpoint["goal_execution"]["transition_history"].pop()
        control = checkpoint["goal_execution"]["transition_history"][-1]
        frozen_source_raw = CHECKPOINT.read_bytes()
        frozen_source = continuation.load_json(CHECKPOINT)
        reconstructed_source = reanchor.reconstructed_seq77_checkpoint_bytes(
            ROOT,
            control,
        )
        checks, contract_document = gate._load_gate_contract(ROOT)
        event_dir_relative = gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
        event_dir = root / event_dir_relative
        event_dir.mkdir(parents=True)
        os.chmod(event_dir, 0o700)
        control_after = control["repository_context_reanchor"]["after"]
        zero = "d" * 64
        repository_payload = {
            "schema_version": "1.0.0",
            "evidence_type": "GATE_REPOSITORY_STATE",
            "gate_event_id": gate.STARTED_EVENT_ID,
            "repository": {
                "head_commit": reanchor.SOURCE_HEAD_COMMIT,
                "branch": "current",
                "object_format": "sha1",
            },
            "git_status_raw": {
                "scope": gate.SNAPSHOT_SCOPE,
                "sha256": zero,
                "byte_count": 0,
                "record_count": 0,
            },
            "dirty_snapshot": {
                "dirty_path_count": 0,
                "path_set_sha256": zero,
                "content_set_sha256": zero,
                "index_state_sha256": zero,
            },
            "checkpoint_controlled_working_snapshot": {
                "base_head": reanchor.SOURCE_HEAD_COMMIT,
                "managed_changed_path_count": control_after[
                    "managed_changed_path_count"
                ],
                "path_set_sha256": control_after["path_set_sha256"],
                "content_set_sha256": control_after["content_set_sha256"],
            },
            "transaction_exclusions": {
                "allowed_rule_count": 2,
                "checkpoint_exact_path": gate.CHECKPOINT_RELATIVE.as_posix(),
                "gate_event_exact_prefix": event_dir_relative.as_posix() + "/",
            },
        }
        runs = []
        repository_output_sha256 = ""
        for index, (check_id, command) in enumerate(checks, start=1):
            output_relative = (
                event_dir_relative / f"{index:02d}-{check_id}.log"
            )
            output = (
                continuation.canonical_json_bytes(repository_payload) + b"\n"
                if check_id == "REPOSITORY_STATE"
                else f"{check_id}: PASS\n".encode()
            )
            output_path = root / output_relative
            output_path.write_bytes(output)
            os.chmod(output_path, 0o600)
            output_sha256 = sha256_bytes(output)
            if check_id == "REPOSITORY_STATE":
                repository_output_sha256 = output_sha256
            runs.append(
                {
                    "check_id": check_id,
                    "command": command,
                    "executed_at": (
                        f"2026-08-23T12:00:02.{index:06d}+09:00"
                    ),
                    "exit_code": 0,
                    "output_path": output_relative.as_posix(),
                    "output_sha256": output_sha256,
                }
            )
        repository_snapshot = gate.repository_snapshot_from_payload(
            repository_payload,
            event_id=gate.STARTED_EVENT_ID,
            output_sha256=repository_output_sha256,
        )
        receipt = {
            "schema_version": "1.1",
            "document_id": gate._document_id(started.EVENT_ID),
            "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
            "gate_purpose": "INITIAL_START",
            "status": "PASS",
            "package_id": gate.PACKAGE_ID,
            "target_transition_event_id": gate.STARTED_EVENT_ID,
            "target_goal_id": gate.TARGET_GOAL_ID,
            "target_goal_content_sha256": gate.TARGET_GOAL_SHA256,
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
            "source_activation_event_sha256": control["event_sha256"],
            "source_checkpoint_sha256": sha256_bytes(reconstructed_source),
            "source_ready_event_sha256": gate.SOURCE_READY_EVENT_SHA256,
            "check_command_contract_version": gate.CONTRACT_VERSION,
            "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
            "implementation_start_gate_contract_binding": (
                gate.expected_contract_binding()
            ),
            "runtime_bindings": [],
            "execution_window": {
                "started_at": "2026-08-23T12:00:02+09:00",
                "ended_at": "2026-08-23T12:00:03+09:00",
            },
            "check_runs": runs,
            "repository_snapshot": repository_snapshot,
            "generated_at": "2026-08-23T12:00:04+09:00",
        }
        receipt_bytes = started.json_bytes(receipt)
        receipt_relative = event_dir_relative / gate.RECEIPT_NAME
        receipt_path = root / receipt_relative
        receipt_path.write_bytes(receipt_bytes)
        os.chmod(receipt_path, 0o600)
        evidence = started.GateEvidence(
            receipt=receipt,
            receipt_bytes=receipt_bytes,
            receipt_binding={
                "document_id": receipt["document_id"],
                "path": receipt_relative.as_posix(),
                "file_sha256": sha256_bytes(receipt_bytes),
            },
            repository_payload=repository_payload,
            event_occurred_at="2026-08-23T12:00:05+09:00",
        )
        final = {
            Path(path): "e" * 64
            for path in checkpoint["working_tree_snapshot"][
                "managed_changed_paths"
            ]
        }
        with mock.patch.object(started, "require_exact_source"):
            projected, _event = started.project_seq78(
                ROOT,
                checkpoint,
                evidence,
                event_id=gate.STARTED_EVENT_ID,
                final_sha256_by_path=final,
            )
        return {
            "checkpoint": projected,
            "reviewed": reviewed,
            "reanchor": reanchor,
            "gate": gate,
            "started": started,
            "review_binding": review_binding,
            "successor_binding": control["contract_supersession"][
                "replacement_contract_binding"
            ],
            "authorization_binding": control["authorization_binding"],
            "runner_binding": control["start_gate_runner_binding"],
            "frozen_source_raw": frozen_source_raw,
            "frozen_source": frozen_source,
            "checks": checks,
            "contract_document": contract_document,
            "receipt": receipt,
            "receipt_path": receipt_path,
            "receipt_bytes": receipt_bytes,
            "event_dir": event_dir,
        }

    @staticmethod
    def _validate_actual_private_suffix(
        root: Path,
        fixture: dict[str, object],
    ) -> list[str]:
        reanchor = fixture["reanchor"]
        gate = fixture["gate"]
        with (
            mock.patch.object(
                continuation,
                "_fp046_r002_seq77_78_modules",
                return_value=(
                    fixture["reviewed"],
                    reanchor,
                    gate,
                    fixture["started"],
                ),
            ),
            mock.patch.object(
                continuation,
                "_validate_fp046_r002_frozen_r011",
                return_value=[],
            ),
            mock.patch.object(
                reanchor,
                "_load_r002_contract",
                return_value=({}, fixture["successor_binding"]),
            ),
            mock.patch.object(
                reanchor,
                "authorization_binding",
                return_value=fixture["authorization_binding"],
            ),
            mock.patch.object(
                reanchor,
                "start_gate_runner_binding",
                return_value=fixture["runner_binding"],
            ),
            mock.patch.object(
                reanchor,
                "transition_review_binding",
                return_value=fixture["review_binding"],
            ),
            mock.patch.object(
                reanchor,
                "load_frozen_source_checkpoint",
                return_value=(
                    fixture["frozen_source_raw"],
                    fixture["frozen_source"],
                ),
            ),
            mock.patch.object(
                reanchor.review_authority,
                "validated_reviewed_at",
                return_value=datetime.fromisoformat(
                    "2026-08-23T11:59:59+09:00"
                ),
            ),
            mock.patch.object(
                gate,
                "_load_gate_contract",
                return_value=(fixture["checks"], fixture["contract_document"]),
            ),
        ):
            return continuation.validate_fp046_r002_seq77_78_boundary(
                root,
                fixture["checkpoint"],
            )

    def test_review_cohort_reanchors_frozen_r011_to_current_control(self) -> None:
        review, _reanchor, _gate, _started = (
            continuation._fp046_r002_seq77_78_modules()
        )
        context = review.prepare_review_context(
            ROOT, require_exact_source=False
        )

        self.assertEqual(len(context.frozen_r011_cohort), 23)
        self.assertEqual(len(context.current_control_cohort), 31)
        self.assertEqual(len(context.control_code_successors), 7)
        self.assertEqual(len(context.added_control_code_bindings), 8)
        self.assertEqual(
            len(context.control_code_successors)
            + len(context.added_control_code_bindings),
            15,
        )
        self.assertTrue(review.REVIEW_DIR.as_posix().endswith("review-rounds/R006"))
        self.assertTrue(review.ROUND_ID.endswith("-R006"))
        self.assertEqual(
            tuple(review.PRESERVED_REVIEW_PATHS),
            (
                review.R001_ASSIGNMENT_REL,
                review.R002_ASSIGNMENT_REL,
                review.R003_ASSIGNMENT_REL,
                review.R004_ASSIGNMENT_REL,
            ),
        )
        self.assertEqual(
            tuple(context.approved_r005_review_bindings),
            tuple(
                {
                    "path": path.as_posix(),
                    "sha256": review.R005_REVIEW_PINS[path][0],
                    "byte_length": review.R005_REVIEW_PINS[path][1],
                }
                for path in review.R005_REVIEW_PATHS
            ),
        )
        self.assertEqual(len(context.session_artifact_bindings), 4)
        for path, actual in zip(
            review.SESSION_ARTIFACT_PATHS,
            context.session_artifact_bindings,
            strict=True,
        ):
            with self.subTest(session_artifact=path.as_posix()):
                self.assertEqual(
                    actual,
                    {
                        "path": path.as_posix(),
                        "sha256": review.SESSION_ARTIFACT_PINS[path][0],
                        "byte_length": review.SESSION_ARTIFACT_PINS[path][1],
                    },
                )
        preserved_r003 = context.superseded_review_assignments[2]
        self.assertEqual(
            preserved_r003["assignment_binding"],
            {
                "path": review.R003_ASSIGNMENT_REL.as_posix(),
                "sha256": (
                    "fb3bf40e349c4edf6cabdd7a8afde5c491802f3d943f8b2b4beda5b84d54658c"
                ),
                "byte_length": 24_009,
            },
        )
        self.assertEqual(
            preserved_r003["confirmed_rejection_findings"],
            ["REVIEW_INPUT_COHORT_ABA_CAN_PUBLISH_SELF_INVALID_EVIDENCE"],
        )

    def test_reviewed_seq78_fixture_uses_frozen_seq76_bytes(self) -> None:
        with mock.patch.object(
            continuation,
            "load_json",
            side_effect=AssertionError("live checkpoint loader was called"),
        ):
            checkpoint = self._reviewed_seq78_fixture()[0]
        history = checkpoint["goal_execution"]["transition_history"]
        self.assertEqual(
            history[75]["event_sha256"],
            continuation.FP046_R002_READY_EVENT_SHA256,
        )
        self.assertEqual(history[76]["sequence"], 77)
        self.assertEqual(history[77]["sequence"], 78)

    def test_private_gate_contract_has_exact_five_control_checks(self) -> None:
        self.assertEqual(
            continuation.FP046_R002_START_GATE_CHECK_IDS,
            [
                "CONTINUATION",
                "GOAL_GRAPH",
                "TEST_LAYER_REGISTRY_VALIDATE",
                "ROOT_FP046_R002_CONTROL_REGRESSION",
                "REPOSITORY_STATE",
            ],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_runtime_bindings(
                ROOT,
                [],
                event_id=continuation.FP046_R002_STARTED_EVENT_ID,
            ),
            [],
        )
        self.assertIn(
            "runtime bindings differ",
            "\n".join(
                continuation._validate_fp046_r002_runtime_bindings(
                    ROOT,
                    [{"path": "forbidden", "file_sha256": "0" * 64}],
                    event_id=continuation.FP046_R002_STARTED_EVENT_ID,
                )
            ),
        )

    def test_validate_start_gate_uses_the_runner_receipt_path(self) -> None:
        _review, _reanchor, gate, _started = (
            continuation._fp046_r002_seq77_78_modules()
        )
        expected_path = (
            gate.GATE_ROOT_RELATIVE
            / gate.STARTED_EVENT_ID
            / gate.RECEIPT_NAME
        ).as_posix()
        event = {
            "event_id": gate.STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
            "implementation_start_gate_binding": {
                "document_id": "WS-FP046-R002-START-GATE-RECEIPT-TEST",
                "path": expected_path,
                "file_sha256": "a" * 64,
            },
        }
        with mock.patch.object(
            continuation,
            "_load_fp008_private_binding",
            return_value=(["receipt path probe"], {}, None, None),
        ) as loader:
            errors = continuation._validate_start_gate(
                ROOT,
                event=event,
                checkpoint={},
                goal_paths={},
                manifest={},
                manifest_sha256="b" * 64,
                activation_sha256="c" * 64,
                activation_occurred_at=None,
            )

        self.assertEqual(errors, ["receipt path probe"])
        self.assertEqual(loader.call_args.kwargs["expected_path"], expected_path)

    def test_frozen_r011_predecessor_is_byte_exact(self) -> None:
        self.assertEqual(
            continuation._validate_fp046_r002_frozen_r011(ROOT),
            [],
        )

    def test_recovery_r014_preserves_r012_and_rejected_r013(self) -> None:
        checkpoint_raw = CHECKPOINT.read_bytes()
        checkpoint = json.loads(checkpoint_raw)
        seq83 = checkpoint["goal_execution"]["transition_history"][82]
        self.assertEqual(
            len(checkpoint_raw),
            continuation.FP046_R002_SEQ83_CHECKPOINT_BYTE_COUNT,
        )
        self.assertEqual(
            hashlib.sha256(checkpoint_raw).hexdigest(),
            continuation.FP046_R002_SEQ83_CHECKPOINT_SHA256,
        )
        self.assertEqual(
            seq83["event_sha256"],
            continuation.FP046_R002_SEQ83_EVENT_SHA256,
        )
        self.assertEqual(
            seq83["transition_control_review_binding"],
            continuation.FP046_R002_RECOVERY_APPROVED_R012_REVIEW_BINDING,
        )
        self.assertTrue(
            continuation.FP046_R002_RECOVERY_REVIEW_DIR.as_posix().endswith(
                "seq78-79/review-rounds/R014"
            )
        )
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_SEQ79_MANAGED_PATH_COUNT,
            998,
        )
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_HISTORICAL_MANAGED_PATH_COUNT,
            1002,
        )
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_SEQ81_MANAGED_PATH_COUNT,
            1006,
        )
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_SEQ82_MANAGED_PATH_COUNT,
            1011,
        )
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_SEQ83_MANAGED_PATH_COUNT,
            1015,
        )
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_MANAGED_PATH_COUNT,
            1020,
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r006(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r007(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r008(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r009(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_rejected_r010(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r011(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r012(ROOT),
            [],
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_rejected_r013(ROOT),
            [],
        )

    def test_boundary_is_dormant_at_seq76(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        self.assertEqual(
            continuation.validate_fp046_r002_seq77_78_boundary(
                ROOT,
                checkpoint,
            ),
            [],
        )

    def test_current_seq83_and_synthetic_seq84_seq85_projections(self) -> None:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
            as correction,
        )
        from scripts import (
            apply_walksafe_fp046_r002_goal_started_seq79_20260824 as started,
        )

        live = continuation.load_json(CHECKPOINT)
        history = live["goal_execution"]["transition_history"]
        self.assertGreaterEqual(len(history), 83)
        self.assertEqual(
            continuation.validate_fp046_r002_seq77_78_boundary(ROOT, live),
            [],
        )
        source = (
            live
            if len(history) == 83
            else json.loads(
                correction.reconstructed_seq83_checkpoint_bytes(ROOT, history[82])
            )
        )
        self.assertEqual(len(source["goal_execution"]["transition_history"]), 83)
        review_binding = {
            role: {
                "path": path.as_posix(),
                "sha256": str(index) * 64,
                "byte_length": 1,
            }
            for index, (role, path) in enumerate(
                zip(
                    ("assignment", "review_result", "independent_review"),
                    continuation.FP046_R002_RECOVERY_REVIEW_PATHS,
                    strict=True,
                ),
                start=1,
            )
        }
        seq84, event84 = correction.project_seq84(
            source,
            managed_paths=correction.exact_seq84_managed_paths(source),
            path_set_sha256="4" * 64,
            content_set_sha256="5" * 64,
            authorization_binding={
                "path": "authorization.json",
                "sha256": "6" * 64,
                "byte_length": 1,
            },
            transition_review_binding=review_binding,
            runner_binding={
                "path": "runner.py",
                "sha256": "7" * 64,
                "byte_length": 1,
            },
        )
        self.assertEqual(
            event84["event_id"],
            continuation.FP046_R002_SIXTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID,
        )
        self.assertEqual(event84["sequence"], 84)
        self.assertEqual(event84["from_status"], event84["to_status"])
        self.assertEqual(event84["status_changes"], {})
        self.assertEqual(
            len(seq84["working_tree_snapshot"]["managed_changed_paths"]),
            continuation.FP046_R002_RECOVERY_MANAGED_PATH_COUNT,
        )
        self.assertEqual(
            seq84["working_tree_snapshot"]["managed_changed_path_count"],
            continuation.FP046_R002_RECOVERY_MANAGED_PATH_COUNT,
        )
        self.assertNotIn(
            continuation.FP046_R002_RECOVERY_FAILED_GATE_LOG,
            seq84["working_tree_snapshot"]["managed_changed_paths"],
        )

        evidence = started.GateEvidence(
            receipt={"repository_snapshot": {"sealed": True}},
            receipt_bytes=b"{}\n",
            receipt_binding={
                "document_id": "WS-SYNTHETIC-SEQ85-RECEIPT",
                "path": "synthetic/receipt.json",
                "file_sha256": "8" * 64,
            },
            repository_payload={},
            event_occurred_at="2026-08-25T03:40:00+09:00",
        )
        final_paths = sorted(
            set(seq84["working_tree_snapshot"]["managed_changed_paths"])
            | {
                started.SCRIPT_RELATIVE.as_posix(),
                started.TEST_RELATIVE.as_posix(),
            }
        )
        with mock.patch.object(started, "require_exact_source", return_value=None):
            seq85, event85 = started.project_seq85(
                ROOT,
                seq84,
                evidence,
                event_id=started.EVENT_ID,
                final_sha256_by_path={Path(path): "9" * 64 for path in final_paths},
            )
        self.assertEqual(event85["event_id"], continuation.FP046_R002_STARTED_EVENT_ID)
        self.assertEqual(event85["sequence"], 85)
        self.assertEqual(event85["from_status"], "READY")
        self.assertEqual(event85["to_status"], "IN_PROGRESS")
        self.assertEqual(
            seq85["working_tree_snapshot"]["managed_changed_path_count"],
            continuation.FP046_R002_RECOVERY_MANAGED_PATH_COUNT,
        )
        self.assertEqual(
            seq85["goal_execution"]["status_by_goal"][
                continuation.FP046_R002_GOAL_ID
            ],
            "IN_PROGRESS",
        )
        contract_errors, checks, binding, _ready = (
            continuation._fp046_r002_start_gate_contract(
                ROOT,
                event=event85,
                checkpoint=seq85,
            )
        )
        self.assertEqual(contract_errors, [])
        self.assertEqual(
            [item["check_id"] for item in checks],
            continuation.FP046_R002_START_GATE_CHECK_IDS,
        )
        self.assertTrue(
            binding["path"].endswith("initial-start-gate-contract-r002.json")
        )

    def test_post_seq85_recovery_count_accepts_live_successor_universe(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        successor = copy.deepcopy(state["transition_history"][-1])
        successor["sequence"] = 86
        successor["event_id"] = "WS-SYNTHETIC-FP046-R002-SUCCESSOR-SEQ86"
        state["transition_history"].append(successor)
        snapshot = checkpoint["working_tree_snapshot"]
        paths = sorted(
            set(snapshot["managed_changed_paths"])
            | {
                "scripts/apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py"
            }
        )
        snapshot["managed_changed_paths"] = paths
        snapshot["managed_changed_path_count"] = len(paths)

        errors = continuation._validate_fp046_r002_recovery_seq78_79(
            ROOT, checkpoint, state["transition_history"]
        )

        self.assertNotIn("FP046 R002 recovery managed path count differs", errors)

    def test_recovery_rejected_r001_assignment_is_exact_and_output_free(
        self,
    ) -> None:
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_rejected_r001(ROOT),
            [],
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            binding = continuation.FP046_R002_RECOVERY_REJECTED_R001_ASSIGNMENT
            assignment = root / binding["path"]
            assignment.parent.mkdir(parents=True)
            assignment.write_bytes((ROOT / binding["path"]).read_bytes())
            os.chmod(assignment, 0o600)
            rejected_output = root / continuation.FP046_R002_RECOVERY_REJECTED_R001_ABSENT_PATHS[0]
            rejected_output.write_bytes(b"forbidden\n")

            errors = continuation._validate_fp046_r002_recovery_rejected_r001(
                root
            )

        self.assertTrue(
            any("output must remain absent" in error for error in errors), errors
        )

    def test_recovery_rejected_r013_assignment_is_exact_and_output_free(
        self,
    ) -> None:
        binding = continuation.FP046_R002_RECOVERY_REJECTED_R013_ASSIGNMENT
        self.assertTrue(binding["path"].endswith("R013/review-assignment.json"))
        self.assertEqual(
            continuation.FP046_R002_RECOVERY_REJECTED_R013_ABSENT_PATHS,
            (
                binding["path"].replace("review-assignment.json", "review-result.json"),
                binding["path"].replace(
                    "review-assignment.json", "independent-review.json"
                ),
            ),
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_rejected_r013(ROOT), []
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            assignment = root / binding["path"]
            assignment.parent.mkdir(parents=True)
            assignment.write_bytes((ROOT / binding["path"]).read_bytes())
            os.chmod(assignment, 0o600)
            rejected_output = (
                root / continuation.FP046_R002_RECOVERY_REJECTED_R013_ABSENT_PATHS[0]
            )
            rejected_output.write_bytes(b"forbidden\n")
            errors = continuation._validate_fp046_r002_recovery_rejected_r013(root)
        self.assertTrue(
            any("rejected R013 output must remain absent" in error for error in errors),
            errors,
        )

    def test_recovery_approved_r014_triad_is_frozen_and_byte_exact(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        seq84 = checkpoint["goal_execution"]["transition_history"][83]
        self.assertEqual(
            seq84["transition_control_review_binding"],
            continuation.FP046_R002_RECOVERY_APPROVED_R014_REVIEW_BINDING,
        )
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r014(ROOT),
            [],
        )

    def test_recovery_approved_r014_byte_tamper_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for binding in (
                continuation.FP046_R002_RECOVERY_APPROVED_R014_REVIEW_BINDING.values()
            ):
                relative = binding["path"]
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((ROOT / relative).read_bytes())
                os.chmod(destination, 0o600)
            assignment = (
                root
                / continuation.FP046_R002_RECOVERY_APPROVED_R014_REVIEW_BINDING[
                    "assignment"
                ]["path"]
            )
            tampered = bytearray(assignment.read_bytes())
            tampered[0] ^= 1
            assignment.write_bytes(tampered)

            errors = continuation._validate_fp046_r002_recovery_approved_r014(
                root
            )

        self.assertEqual(
            errors,
            ["FP046 R002 recovery approved R014 assignment binding differs"],
        )

    def test_seq84_historical_r014_does_not_reload_mutable_current_cohort(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        recovery_review = continuation._fp046_r002_seq78_79_modules()[0]
        with (
            mock.patch.object(
                recovery_review,
                "transition_review_binding",
                side_effect=AssertionError("mutable R014 cohort was reloaded"),
            ) as transition_binding,
            mock.patch.object(
                recovery_review,
                "validated_reviewed_at",
                side_effect=AssertionError("mutable R014 cohort was reloaded"),
            ) as reviewed_at,
        ):
            errors = continuation.validate_fp046_r002_seq77_78_boundary(
                ROOT,
                checkpoint,
            )

        self.assertEqual(errors, [])
        transition_binding.assert_not_called()
        reviewed_at.assert_not_called()

    def test_recovery_approved_r002_triad_is_byte_exact(self) -> None:
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r002(ROOT),
            [],
        )

    def test_recovery_approved_r003_triad_is_byte_exact(self) -> None:
        self.assertEqual(
            continuation._validate_fp046_r002_recovery_approved_r003(ROOT),
            [],
        )

    def test_canonical_seq83_remains_outside_static_seq77_authority(self) -> None:
        from scripts import (
            build_walksafe_fp046_r002_seq78_79_recovery_review_20260824
            as recovery_review,
        )

        self.assertEqual(
            recovery_review.review_source_checkpoint_binding(ROOT)["sequence"], 83
        )

    def test_seq80_checkpoint_uses_stored_failure_and_not_active_r010(
        self,
    ) -> None:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
            as correction,
        )

        live = continuation.load_json(CHECKPOINT)
        history = live["goal_execution"]["transition_history"]
        checkpoint = (
            live
            if len(history) == 80
            else json.loads(
                correction.reconstructed_seq80_checkpoint_bytes(ROOT, history[79])
            )
        )
        review = continuation._fp046_r002_seq78_79_modules()[0]
        historical = correction.validate_seq80_history_suffix
        with (
            mock.patch.object(
                review,
                "transition_review_binding",
                side_effect=AssertionError("active R011 review was loaded"),
            ) as binding,
            mock.patch.object(
                review,
                "validated_reviewed_at",
                side_effect=AssertionError("active R011 review was loaded"),
            ) as reviewed_at,
            mock.patch.object(
                correction,
                "validate_seq80_history_suffix",
                wraps=historical,
            ) as historical_validator,
        ):
            event = correction.validate_seq80_history_suffix(
                ROOT, checkpoint, require_live_snapshot=False
            )

        self.assertEqual(event, checkpoint["goal_execution"]["transition_history"][79])
        binding.assert_not_called()
        reviewed_at.assert_not_called()
        self.assertTrue(
            any(
                call.kwargs.get("require_live_snapshot") is False
                for call in historical_validator.call_args_list
            )
        )

    def test_seq80_failed_002_physical_check_is_live_tail_only(self) -> None:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
            as correction,
        )

        live = continuation.load_json(CHECKPOINT)
        seq80_checkpoint = json.loads(
            correction.reconstructed_seq80_checkpoint_bytes(
                ROOT, live["goal_execution"]["transition_history"][79]
            )
        )
        physical_loader = correction.review.failed_gate_attempt_002_binding
        with mock.patch.object(
            correction.review,
            "failed_gate_attempt_002_binding",
            wraps=physical_loader,
        ) as live_loader:
            event = correction.validate_seq80_history_suffix(
                ROOT, seq80_checkpoint, require_live_snapshot=True
            )
        self.assertEqual(
            event["event_sha256"],
            continuation.FP046_R002_SEQ80_EVENT_SHA256,
        )
        live_loader.assert_called_once_with(ROOT)

        descendant = copy.deepcopy(live)
        with mock.patch.object(
            correction.review,
            "failed_gate_attempt_002_binding",
            side_effect=AssertionError("historical physical -002 was loaded"),
        ) as descendant_loader:
            event = correction.validate_seq80_history_suffix(
                ROOT, descendant, require_live_snapshot=True
            )
        self.assertEqual(
            event["event_sha256"],
            continuation.FP046_R002_SEQ80_EVENT_SHA256,
        )
        descendant_loader.assert_not_called()

    def test_unknown_r002_reanchor_is_rejected(self) -> None:
        event = {
            "sequence": 77,
            "event_id": "WS-UNKNOWN-FP046-R002-REANCHOR",
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        self.assertEqual(
            continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=continuation.load_json(CHECKPOINT),
                history=[],
            ),
            ["FP046 R002 start-control reanchor event is not recognized"],
        )

    def test_seq79_r002_reanchor_routes_to_exact_correction_validator(self) -> None:
        event = {
            "sequence": 79,
            "event_id": continuation.FP046_R002_RECOVERY_CONTROL_REANCHOR_EVENT_ID,
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_seq79_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[],
                ),
                [],
            )
        correction.validate_seq79_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_seq80_reanchor_uses_stored_history_for_descendant(self) -> None:
        event = {
            "sequence": (
                continuation.FP046_R002_SECOND_RECOVERY_CONTROL_REANCHOR_SEQUENCE
            ),
            "event_id": (
                continuation.FP046_R002_SECOND_RECOVERY_CONTROL_REANCHOR_EVENT_ID
            ),
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_seq80_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[{}] * 81,
                ),
                [],
            )
        correction.validate_seq80_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_exact_seq80_reanchor_checker_uses_stored_failed_attempt(self) -> None:
        event = {
            "sequence": (
                continuation.FP046_R002_SECOND_RECOVERY_CONTROL_REANCHOR_SEQUENCE
            ),
            "event_id": (
                continuation.FP046_R002_SECOND_RECOVERY_CONTROL_REANCHOR_EVENT_ID
            ),
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_seq80_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[{}] * 80,
                ),
                [],
            )
        correction.validate_seq80_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_seq81_r002_reanchor_routes_to_historical_correction_validator(self) -> None:
        event = {
            "sequence": (
                continuation.FP046_R002_THIRD_RECOVERY_CONTROL_REANCHOR_SEQUENCE
            ),
            "event_id": (
                continuation.FP046_R002_THIRD_RECOVERY_CONTROL_REANCHOR_EVENT_ID
            ),
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_seq81_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[{}] * 81,
                ),
                [],
            )
        correction.validate_seq81_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_seq82_r002_reanchor_routes_to_historical_correction_validator(self) -> None:
        event = {
            "sequence": (
                continuation.FP046_R002_FOURTH_RECOVERY_CONTROL_REANCHOR_SEQUENCE
            ),
            "event_id": (
                continuation.FP046_R002_FOURTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID
            ),
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_seq82_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[{}] * 82,
                ),
                [],
            )
        correction.validate_seq82_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_seq83_r002_reanchor_routes_to_historical_correction_validator(self) -> None:
        event = {
            "sequence": (
                continuation.FP046_R002_FIFTH_RECOVERY_CONTROL_REANCHOR_SEQUENCE
            ),
            "event_id": (
                continuation.FP046_R002_FIFTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID
            ),
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_seq83_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[{}] * 83,
                ),
                [],
            )
        correction.validate_seq83_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_seq84_r002_reanchor_routes_to_current_correction_validator(self) -> None:
        event = {
            "sequence": (
                continuation.FP046_R002_SIXTH_RECOVERY_CONTROL_REANCHOR_SEQUENCE
            ),
            "event_id": (
                continuation.FP046_R002_SIXTH_RECOVERY_CONTROL_REANCHOR_EVENT_ID
            ),
            "subject_goal_id": continuation.FP046_R002_GOAL_ID,
        }
        correction = mock.Mock()
        correction.validate_history_suffix.return_value = event
        correction.strict_json_equal.return_value = True
        import scripts

        with mock.patch.object(
            scripts,
            "apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824",
            correction,
        ), mock.patch.dict(
            sys.modules,
            {
                "scripts.apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824": correction,
            },
        ):
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint={},
                    history=[{}] * 84,
                ),
                [],
            )
        correction.validate_history_suffix.assert_called_once_with(
            ROOT, {}, require_live_snapshot=False
        )

    def test_burned_seq78_start_cannot_be_hidden_by_later_suffix(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            fixture = self._actual_private_suffix_fixture(root)
            checkpoint = fixture["checkpoint"]
            history = checkpoint["goal_execution"]["transition_history"]
            seq79 = {
                "sequence": 79,
                "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-TEST-079",
                "event_type": "GOAL_MATERIALIZED",
                "occurred_on": "2026-08-23",
                "occurred_at": "2026-08-23T12:00:06+09:00",
                "previous_event_sha256": history[-1]["event_sha256"],
                "materialized_goal_id": "WS-GOAL-POST-SEQ78-TEST-R001",
                "from_status": None,
                "to_status": "PLANNED",
                "status_changes": {
                    "WS-GOAL-POST-SEQ78-TEST-R001": "PLANNED"
                },
            }
            seq79["event_sha256"] = continuation.event_sha256(seq79)
            history.append(seq79)
            checkpoint["goal_execution"][
                "transition_history_anchor_sha256"
            ] = seq79["event_sha256"]
            checkpoint["goal_execution"]["validation_cutoff_at"] = seq79[
                "occurred_at"
            ]

            review = continuation._fp046_r002_seq77_78_modules()[0]
            checkpoint_path = root / review.SOURCE_CHECKPOINT["path"]
            checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
            checkpoint_path.write_bytes(
                continuation.canonical_json_bytes(checkpoint) + b"\n"
            )
            os.chmod(checkpoint_path, 0o600)
            reviewed_context = fixture[
                "reviewed"
            ].validate_post_review.return_value

            def validate_with_actual_suffix_authority(candidate_root: Path):
                review._require_allowed_checkpoint(candidate_root)
                return reviewed_context

            fixture["reviewed"].validate_post_review.side_effect = (
                validate_with_actual_suffix_authority
            )

            self.assertIn(
                "burned start event ID",
                "\n".join(self._validate_actual_private_suffix(root, fixture)),
            )

    def test_seq77_nested_json_types_fail_closed(self) -> None:
        for value in (False, 0.0):
            with self.subTest(value=repr(value)):
                fixture = self._reviewed_seq78_fixture()
                checkpoint = fixture[0]
                history = checkpoint["goal_execution"]["transition_history"]
                history[76]["claim_boundary"][
                    "product_implementation_credit_delta"
                ] = value
                history[76]["event_sha256"] = continuation.event_sha256(
                    history[76]
                )
                history[77]["previous_event_sha256"] = history[76][
                    "event_sha256"
                ]
                history[77]["event_sha256"] = continuation.event_sha256(
                    history[77]
                )

        self.assertIn(
            "seq77 event identity differs",
            "\n".join(self._validate_reviewed_fixture(*fixture)),
        )

    def test_actual_suffix_validators_reject_receipt_authority_drift(
        self,
    ) -> None:
        for field, value in (
            ("schema_version", "1.0"),
            ("source_ready_event_sha256", "f" * 64),
            ("source_checkpoint_sha256", "f" * 64),
        ):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                fixture = self._actual_private_suffix_fixture(root)
                receipt = copy.deepcopy(fixture["receipt"])
                receipt[field] = value
                receipt_bytes = fixture["started"].json_bytes(receipt)
                receipt_path = fixture["receipt_path"]
                receipt_path.write_bytes(receipt_bytes)
                os.chmod(receipt_path, 0o600)
                event = fixture["checkpoint"]["goal_execution"][
                    "transition_history"
                ][77]
                event["implementation_start_gate_binding"][
                    "file_sha256"
                ] = sha256_bytes(receipt_bytes)
                event["event_sha256"] = continuation.event_sha256(event)

                self.assertTrue(
                    self._validate_actual_private_suffix(root, fixture)
                )

    def test_actual_suffix_validators_reject_resealed_wrong_document_id(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            fixture = self._actual_private_suffix_fixture(root)
            receipt = copy.deepcopy(fixture["receipt"])
            receipt["document_id"] = "WS-FP046-R002-WRONG-RECEIPT"
            receipt_bytes = fixture["started"].json_bytes(receipt)
            receipt_path = fixture["receipt_path"]
            receipt_path.write_bytes(receipt_bytes)
            os.chmod(receipt_path, 0o600)
            event = fixture["checkpoint"]["goal_execution"][
                "transition_history"
            ][77]
            event["implementation_start_gate_binding"].update(
                {
                    "document_id": receipt["document_id"],
                    "file_sha256": sha256_bytes(receipt_bytes),
                }
            )
            event["event_sha256"] = continuation.event_sha256(event)

            self.assertTrue(
                self._validate_actual_private_suffix(root, fixture)
            )

    def test_actual_suffix_validators_reject_log_or_inventory_drift(
        self,
    ) -> None:
        for mutation in ("log", "inventory"):
            with (
                self.subTest(mutation=mutation),
                tempfile.TemporaryDirectory() as temp_dir,
            ):
                root = Path(temp_dir)
                fixture = self._actual_private_suffix_fixture(root)
                if mutation == "log":
                    target = fixture["event_dir"] / "01-CONTINUATION.log"
                    target.write_bytes(b"tampered\n")
                    os.chmod(target, 0o600)
                else:
                    target = fixture["event_dir"] / "unexpected-private-file"
                    target.write_bytes(b"unexpected\n")
                    os.chmod(target, 0o600)

                self.assertTrue(
                    self._validate_actual_private_suffix(root, fixture)
                )

    def test_later_suffix_cannot_hide_seq77_authority_tampering(self) -> None:
        fixture = self._reviewed_seq78_fixture()
        checkpoint = fixture[0]
        history = checkpoint["goal_execution"]["transition_history"]
        history[76]["authorization_binding"] = {"forged": True}
        history[76]["event_sha256"] = continuation.event_sha256(history[76])
        history[77]["previous_event_sha256"] = history[76]["event_sha256"]
        history[77]["event_sha256"] = continuation.event_sha256(history[77])

        self.assertIn(
            "seq77 event identity differs",
            "\n".join(self._validate_reviewed_fixture(*fixture)),
        )

    def test_exact_seq77_78_prefix_survives_later_suffix(self) -> None:
        self.test_later_suffix_cannot_hide_seq77_authority_tampering()

    def test_burned_start_ids_are_evidence_only(self) -> None:
        expected = {
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001",
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-002",
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-003",
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-004",
        }
        self.assertEqual(continuation.FP046_R002_BURNED_STARTED_EVENT_IDS, expected)
        for event_id in sorted(expected):
            with self.subTest(event_id=event_id):
                fixture = self._reviewed_seq78_fixture()
                event = fixture[0]["goal_execution"]["transition_history"][77]
                event["event_id"] = event_id
                event["event_sha256"] = continuation.event_sha256(event)
                self.assertIn(
                    "burned start event ID",
                    "\n".join(self._validate_reviewed_fixture(*fixture)),
                )

    def test_seq79_replay_and_unknown_type_remain_fail_closed(self) -> None:
        fixture = self._reviewed_seq78_fixture()
        checkpoint = fixture[0]
        history = checkpoint["goal_execution"]["transition_history"]
        seq79 = copy.deepcopy(history[77])
        seq79["sequence"] = 79
        seq79["event_type"] = "UNKNOWN_FUTURE_EVENT"
        seq79["previous_event_sha256"] = history[77]["event_sha256"]
        seq79["event_sha256"] = continuation.event_sha256(seq79)
        history.append(seq79)

        errors = continuation.validate_generic_event_order(history)

        self.assertTrue(any("ID is invalid or duplicated" in error for error in errors))
        self.assertTrue(any("type is invalid" in error for error in errors))


class WalkSafeFp022CompletionSuffixTest(unittest.TestCase):
    def test_seq71_does_not_require_r001_through_r004_or_r009_through_r011_reviews(
        self,
    ) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)

        self.assertEqual(
            continuation.validate_fp046_npc_r002_seq72_boundary(
                ROOT,
                checkpoint,
            ),
            [],
        )

    def test_seq68_replays_the_frozen_r031_review_binding(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        event = checkpoint["goal_execution"]["transition_history"][67]
        self.assertEqual(
            continuation._fp022_frozen_transition_review_binding(ROOT),
            event["transition_control_review_binding"],
        )

    def test_seq68_canonical_snapshot_is_historical_after_completion_updates(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        history = checkpoint["goal_execution"]["transition_history"]
        event = history[67]
        gap = next(
            row
            for row in checkpoint["canonical_bindings"]
            if row["role"] == "IMPLEMENTATION_GAP"
        )
        gap["file_sha256"] = "0" * 64
        errors = continuation._validate_fp022_control_reanchor_seq68(
            ROOT,
            event=event,
            checkpoint=checkpoint,
            history=history,
        )
        self.assertNotIn("canonical snapshot", "\n".join(errors))

    def test_completion_review_paths_follow_the_current_review_builder(self) -> None:
        from scripts import (
            build_walksafe_fp022_completion_seq70_71_review_20260814 as review,
        )

        self.assertEqual(
            continuation._fp022_completion_review_paths(),
            {
                "assignment": review.ASSIGNMENT_REL.as_posix(),
                "review_result": review.RESULT_REL.as_posix(),
                "independent_review": review.INDEPENDENT_REL.as_posix(),
            },
        )

    def test_seq69_source_does_not_claim_completion(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        self.assertEqual(
            continuation.validate_fp022_completion_seq70_71(ROOT, checkpoint),
            [],
        )

    def test_seq70_without_adjacent_seq71_fails_closed(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        if len(state["transition_history"]) >= 71:
            del state["transition_history"][69:]
        seq70 = copy.deepcopy(state["transition_history"][-1])
        seq70.update(
            {
                "sequence": 70,
                "event_id": continuation.FP022_COMPLETION_UPDATE_EVENT_ID,
                "event_type": "CANONICAL_BINDINGS_UPDATED",
                "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
            }
        )
        seq70["event_sha256"] = continuation.event_sha256(seq70)
        state["transition_history"].append(seq70)
        errors = continuation.validate_fp022_completion_seq70_71(ROOT, checkpoint)
        self.assertEqual(
            errors,
            ["FP022 seq70 producer transaction lacks adjacent seq71 completion"],
        )

    def test_resealed_wrong_seq70_71_ids_are_rejected(self) -> None:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        if len(state["transition_history"]) >= 71:
            del state["transition_history"][69:]
        for sequence, event_type in ((70, "CANONICAL_BINDINGS_UPDATED"), (71, "GOAL_COMPLETED")):
            event = copy.deepcopy(state["transition_history"][-1])
            event.update(
                {
                    "sequence": sequence,
                    "event_id": f"WS-FORGED-FP022-{sequence}",
                    "event_type": event_type,
                    "previous_event_sha256": state["transition_history"][-1]["event_sha256"],
                }
            )
            event["event_sha256"] = continuation.event_sha256(event)
            state["transition_history"].append(event)
        errors = continuation.validate_fp022_completion_seq70_71(ROOT, checkpoint)
        self.assertIn("FP022 completion seq70 ID", "\n".join(errors))
        self.assertIn("FP022 completion seq71 ID", "\n".join(errors))


def _fp046_r002_completion_checkpoint() -> dict[str, object]:
    source = continuation.load_json(CHECKPOINT)
    del source["goal_execution"]["transition_history"][85:]
    completion_binding = {
        "role": continuation.FP046_R002_COMPLETION_ROLE,
        "document_id": (
            "WS-FP046-R002-CONSENT-WITHDRAWAL-DELETION-"
            "WORK-ITEM-COMPLETION-20260825-R005"
        ),
        "path": (
            "docs/control/execution/goal-results/"
            "WS-GOAL-EPIC-03-FP-046-R002/completion-receipt-r005.json"
        ),
        "file_sha256": "a" * 64,
    }
    gap = {
        "metadata": {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260825-030",
            "version": "0.30.0",
        },
        "assessments": [],
        "summary": {"status_counts": {}},
        "implementation_snapshot": {},
    }
    backlog = {
        "metadata": {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260825-030"
        },
        "epics": [],
    }
    evidence = fp046_completion.CompletionEvidence(
        raw_by_path={
            fp046_completion.GAP_JSON_REL: fp046_completion.json_bytes(gap),
            fp046_completion.BACKLOG_JSON_REL: fp046_completion.json_bytes(backlog),
        },
        bindings_by_role={
            "IMPLEMENTATION_GAP": {
                "role": "IMPLEMENTATION_GAP",
                "document_id": gap["metadata"]["report_id"],
                "path": fp046_completion.GAP_JSON_REL.as_posix(),
                "file_sha256": "b" * 64,
            },
            "IMPLEMENTATION_BACKLOG": {
                "role": "IMPLEMENTATION_BACKLOG",
                "document_id": backlog["metadata"]["backlog_id"],
                "path": fp046_completion.BACKLOG_JSON_REL.as_posix(),
                "file_sha256": "c" * 64,
            },
            continuation.FP046_R002_COMPLETION_ROLE: completion_binding,
        },
        update_occurred_at="2026-08-25T03:08:31+09:00",
        completion_occurred_at="2026-08-25T03:08:32+09:00",
        final_sha256_by_path={},
        transition_review_binding={
            role: {
                "path": (
                    "docs/control/execution/workstream-transitions/seq86-87/"
                    f"review-rounds/R005/{name}"
                ),
                "sha256": digest * 64,
                "byte_length": 1,
            }
            for role, name, digest in (
                ("assignment", "assignment.json", "d"),
                ("review_result", "review-result.json", "e"),
                ("independent_review", "independent-review.json", "f"),
            )
        },
    )

    def preserve_runtime(
        _root: Path,
        projected: dict[str, object],
        _frontier: list[str],
    ) -> tuple[dict[str, object], dict[str, object]]:
        state = projected["goal_execution"]
        return (
            copy.deepcopy(state["artifact_work_queue"]),
            copy.deepcopy(state["completion_boundary"]),
        )

    projected, _update, _completion = fp046_completion.project_seq86_87(
        ROOT,
        source,
        evidence,
        runtime_deriver=preserve_runtime,
    )
    return projected


def _reseal_fp046_r002_completion_pair(checkpoint: dict[str, object]) -> None:
    state = checkpoint["goal_execution"]
    update, completion = state["transition_history"][85:87]
    update["event_sha256"] = continuation.event_sha256(update)
    completion["previous_event_sha256"] = update["event_sha256"]
    completion["canonical_update_event_sha256"] = update["event_sha256"]
    completion["event_sha256"] = continuation.event_sha256(completion)
    state["transition_history_anchor_sha256"] = completion["event_sha256"]


class WalkSafeFp046R002CompletionSuffixTest(unittest.TestCase):
    def test_exact_seq86_deferred_reopen_rejects_forged_authority(self) -> None:
        history = continuation.load_json(CHECKPOINT)["goal_execution"][
            "transition_history"
        ]
        expected = continuation._fp046_r002_deferred_reopen_after_completion(
            history
        )
        self.assertEqual(
            set(expected), {continuation.FP046_R002_FP048_R001_GOAL_ID}
        )

        mutations = {
            "id": lambda events: events[85].__setitem__(
                "event_id", "WS-FORGED-FP046-R002-SEQ86"
            ),
            "hash": lambda events: events[85].__setitem__(
                "event_sha256", "0" * 64
            ),
            "impact": lambda events: events[85][
                "impact_disposition_by_goal"
            ][continuation.FP046_R002_FP048_R001_GOAL_ID].__setitem__(
                "result", "NO_REOPEN"
            ),
            "reopen_seal": lambda events: events[85][
                "reopened_completion_event_sha256_by_goal"
            ].__setitem__(
                continuation.FP046_R002_FP048_R001_GOAL_ID, "0" * 64
            ),
            "reordered": lambda events: events.__setitem__(
                slice(85, 87), list(reversed(events[85:87]))
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                forged = copy.deepcopy(history)
                mutate(forged)
                self.assertEqual(
                    continuation._fp046_r002_deferred_reopen_after_completion(
                        forged
                    ),
                    {},
                )

    def test_seq85_is_dormant(self) -> None:
        checkpoint = _fp046_r002_completion_checkpoint()
        del checkpoint["goal_execution"]["transition_history"][85:]

        self.assertEqual(
            continuation.validate_fp046_r002_completion_seq86_87(checkpoint),
            [],
        )

    def test_seq86_without_adjacent_seq87_fails_closed(self) -> None:
        checkpoint = _fp046_r002_completion_checkpoint()
        del checkpoint["goal_execution"]["transition_history"][86:]

        self.assertEqual(
            continuation.validate_fp046_r002_completion_seq86_87(checkpoint),
            [
                "FP046 R002 seq86 producer transaction lacks adjacent "
                "seq87 completion"
            ],
        )

    def test_valid_synthetic_pair_and_historical_descendant(self) -> None:
        checkpoint = _fp046_r002_completion_checkpoint()
        self.assertEqual(
            continuation.validate_fp046_r002_completion_seq86_87(checkpoint),
            [],
        )

        state = checkpoint["goal_execution"]
        state["transition_history"].append({"sequence": 88})
        state["focus_goal_id"] = "WS-GOAL-SYNTHETIC-DESCENDANT"
        state["ready_frontier_goal_ids"] = ["WS-GOAL-SYNTHETIC-DESCENDANT"]
        self.assertEqual(
            continuation.validate_fp046_r002_completion_seq86_87(checkpoint),
            [],
        )

    def test_reviewed_core_allows_only_adjacent_producer_completion(self) -> None:
        checkpoint = _fp046_r002_completion_checkpoint()
        errors = continuation.validate_fp046_npc_r002_seq72_boundary(
            ROOT, checkpoint
        )
        self.assertNotIn("approval-neutral reviewed core", "\n".join(errors))

        mutations = {
            "event_type": lambda update, completion: update.__setitem__(
                "event_type", "WORK_SESSION_RESUMED"
            ),
            "producer": lambda update, completion: update.__setitem__(
                "produced_by_goal_id",
                continuation.FP046_R002_COMPLETION_PARENT_GOAL_ID,
            ),
            "pending_completion": lambda update, completion: completion.__setitem__(
                "event_type", "WORK_SESSION_RESUMED"
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                tampered = _fp046_r002_completion_checkpoint()
                update, completion = tampered["goal_execution"][
                    "transition_history"
                ][85:87]
                mutate(update, completion)
                _reseal_fp046_r002_completion_pair(tampered)

                errors = continuation.validate_fp046_npc_r002_seq72_boundary(
                    ROOT, tampered
                )
                self.assertIn(
                    "approval-neutral reviewed core", "\n".join(errors)
                )

    def test_seq85_descendant_receipt_reconstruction_preserves_order(self) -> None:
        from scripts import (
            apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
            as correction,
        )

        descendant = _fp046_r002_completion_checkpoint()
        real_safe_file = correction._safe_file

        def validate(serialized: str) -> list[str]:
            projected = json.loads(serialized)
            with tempfile.TemporaryDirectory() as temporary:
                checkpoint_path = Path(temporary) / "checkpoint.json"
                checkpoint_path.write_text(serialized, encoding="utf-8")

                def safe_file(
                    root: Path,
                    relative: Path,
                    *,
                    modes: frozenset[int] | None = None,
                ) -> Path:
                    if relative == correction.CHECKPOINT_RELATIVE:
                        return checkpoint_path
                    return real_safe_file(root, relative, modes=modes)

                with mock.patch.object(
                    correction, "_safe_file", side_effect=safe_file
                ):
                    return continuation._validate_fp046_r002_recovery_seq78_79(
                        ROOT,
                        projected,
                        projected["goal_execution"]["transition_history"],
                    )

        preserved = json.dumps(descendant, ensure_ascii=False, indent=2) + "\n"
        rewritten = (
            json.dumps(
                descendant,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            + "\n"
        )
        self.assertEqual(validate(preserved), [])
        self.assertEqual(
            validate(rewritten), ["seq85 PASS receipt authority differs"]
        )

    def test_resealed_seq85_receipt_binding_tamper_is_rejected(self) -> None:
        checkpoint = _fp046_r002_completion_checkpoint()
        state = checkpoint["goal_execution"]
        seq85 = state["transition_history"][84]
        seq85["implementation_start_gate_binding"]["file_sha256"] = "0" * 64
        seq85["event_sha256"] = continuation.event_sha256(seq85)
        state["transition_history"][85]["previous_event_sha256"] = seq85[
            "event_sha256"
        ]
        _reseal_fp046_r002_completion_pair(checkpoint)

        errors = continuation._validate_fp046_r002_recovery_seq78_79(
            ROOT, checkpoint, state["transition_history"]
        )
        self.assertIn(
            "FP046 R002 seq85 start event identity differs", errors
        )

    def test_resealed_id_hash_binding_and_impact_tamper_are_rejected(self) -> None:
        mutations = {
            "id": lambda update, completion: update.__setitem__(
                "event_id", "WS-FORGED-FP046-R002-SEQ86"
            ),
            "hash": lambda update, completion: update.__setitem__(
                "previous_event_sha256", "0" * 64
            ),
            "binding": lambda update, completion: update[
                "producer_completion_receipt_binding"
            ].__setitem__("file_sha256", "0" * 64),
            "impact": lambda update, completion: update[
                "impact_disposition_by_goal"
            ][continuation.FP046_R002_FP048_R001_GOAL_ID].__setitem__(
                "result", "NO_REOPEN"
            ),
            "reopen_seal": lambda update, completion: update[
                "reopened_completion_event_sha256_by_goal"
            ].__setitem__(
                continuation.FP046_R002_FP048_R001_GOAL_ID,
                "0" * 64,
            ),
            "failed_r001_review": lambda update, completion: update[
                "transition_control_review_binding"
            ]["assignment"].__setitem__(
                "path",
                "docs/control/execution/workstream-transitions/seq86-87/"
                "review-rounds/R001/assignment.json",
            ),
            "failed_r002_review": lambda update, completion: update[
                "transition_control_review_binding"
            ]["assignment"].__setitem__(
                "path",
                "docs/control/execution/workstream-transitions/seq86-87/"
                "review-rounds/R002/assignment.json",
            ),
            "failed_r003_review": lambda update, completion: update[
                "transition_control_review_binding"
            ]["assignment"].__setitem__(
                "path",
                "docs/control/execution/workstream-transitions/seq86-87/"
                "review-rounds/R003/assignment.json",
            ),
            "failed_r004_review": lambda update, completion: update[
                "transition_control_review_binding"
            ]["assignment"].__setitem__(
                "path",
                "docs/control/execution/workstream-transitions/seq86-87/"
                "review-rounds/R004/assignment.json",
            ),
            "failed_r002_receipt": lambda update, completion: update[
                "producer_completion_receipt_binding"
            ].__setitem__(
                "path",
                "docs/control/execution/goal-results/"
                f"{continuation.FP046_R002_GOAL_ID}/completion-receipt.json",
            ),
            "failed_r003_receipt": lambda update, completion: update[
                "producer_completion_receipt_binding"
            ].__setitem__(
                "path",
                "docs/control/execution/goal-results/"
                f"{continuation.FP046_R002_GOAL_ID}/completion-receipt-r003.json",
            ),
            "failed_r004_receipt": lambda update, completion: update[
                "producer_completion_receipt_binding"
            ].__setitem__(
                "path",
                "docs/control/execution/goal-results/"
                f"{continuation.FP046_R002_GOAL_ID}/completion-receipt-r004.json",
            ),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                checkpoint = _fp046_r002_completion_checkpoint()
                update, completion = checkpoint["goal_execution"][
                    "transition_history"
                ][85:87]
                mutate(update, completion)
                _reseal_fp046_r002_completion_pair(checkpoint)

                self.assertTrue(
                    continuation.validate_fp046_r002_completion_seq86_87(
                        checkpoint
                    ),
                    label,
                )

    def test_validate_dispatches_exact_completion_suffix_validator(self) -> None:
        checkpoint = {"goal_execution": {"transition_history": []}}
        sentinel = "FP046 R002 exact completion suffix dispatcher sentinel"
        with (
            mock.patch.object(
                continuation,
                "validate_frozen_v23_boundary",
                return_value=([], {}),
            ),
            mock.patch.object(continuation, "load_json", return_value=checkpoint),
            mock.patch.object(
                continuation,
                "validate_seq39_canonical_binding_authorization_request",
                return_value=[],
            ),
            mock.patch.object(
                continuation,
                "validate_seq39_canonical_binding_update",
                return_value=[],
            ),
            mock.patch.object(
                continuation,
                "validate_fp022_completion_seq70_71",
                return_value=[],
            ),
            mock.patch.object(
                continuation,
                "validate_fp046_r002_completion_seq86_87",
                return_value=[sentinel],
            ) as exact,
            mock.patch.object(
                continuation,
                "validate_working_snapshot",
                return_value=[],
            ),
        ):
            errors = continuation.validate(ROOT, CHECKPOINT)

        self.assertEqual(errors, [sentinel])
        exact.assert_called_once_with(checkpoint)


class WalkSafeFp048R002Seq90Seq91BoundaryTest(unittest.TestCase):
    EDGES = {"modified": [], "added": []}

    @staticmethod
    def _seq90_checkpoint() -> tuple[dict[str, object], dict[str, object]]:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        published = history[89] if len(history) >= 90 else None
        if published is not None:
            assert published["sequence"] == continuation.FP048_R002_CONTROL_REANCHOR_SEQUENCE
            assert published["event_id"] == continuation.FP048_R002_CONTROL_REANCHOR_EVENT_ID
            assert published["event_sha256"] == continuation.event_sha256(published)
            repository_after = copy.deepcopy(
                published["repository_context_reanchor"]["after"]
            )
            history[:] = history[:89]
            state["transition_history_anchor_sha256"] = history[-1]["event_sha256"]
            state["validation_cutoff_at"] = history[-1]["occurred_at"]
            state["status_by_goal"][continuation.FP048_R002_GOAL_ID] = "READY"
            checkpoint["current_work"]["status"] = "READY"
            snapshot = checkpoint["working_tree_snapshot"]
            snapshot.update(
                {
                    "managed_changed_path_count": repository_after[
                        "managed_changed_path_count"
                    ],
                    "path_set_sha256": repository_after["path_set_sha256"],
                    "content_set_sha256": repository_after[
                        "content_set_sha256"
                    ],
                }
            )
            mirror = checkpoint["session_handoff"][
                "source_commit_or_snapshot"
            ]
            mirror.update(
                {
                    "base_commit": repository_after["base_commit"],
                    "current_head": repository_after["current_head"],
                }
            )
        else:
            repository_after = continuation._fp048_r002_reanchor_repository_after(
                checkpoint,
                history,
            )
        ready = history[88]
        event = {
            "sequence": continuation.FP048_R002_CONTROL_REANCHOR_SEQUENCE,
            "event_id": continuation.FP048_R002_CONTROL_REANCHOR_EVENT_ID,
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_on": "2026-08-26",
            "occurred_at": "2026-08-26T02:00:00+09:00",
            "previous_focus_goal_id": continuation.FP048_R002_GOAL_ID,
            "previous_focus_content_sha256": continuation.FP048_R002_GOAL_SHA256,
            "focus_goal_id": continuation.FP048_R002_GOAL_ID,
            "focus_goal_content_sha256": continuation.FP048_R002_GOAL_SHA256,
            "subject_goal_id": continuation.FP048_R002_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "static_plan_manifest_sha256": ready[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {},
            "runtime_after": copy.deepcopy(ready["runtime_after"]),
            "blockers_after": copy.deepcopy(ready["blockers_after"]),
            "blocker_resolution_ids_after": copy.deepcopy(
                ready["blocker_resolution_ids_after"]
            ),
            "source_checkpoint_version": "1.25.0",
            "evidence_refs": [],
            "source_checkpoint_binding": {},
            "source_ready_event_binding": {
                "event_id": continuation.FP048_R002_READY_EVENT_ID,
                "event_sha256": continuation.FP048_R002_READY_EVENT_SHA256,
                "goal_id": continuation.FP048_R002_GOAL_ID,
                "sequence": 89,
                "status": "READY",
            },
            "contract_supersession": {
                "previous_contract_binding": copy.deepcopy(
                    ready["implementation_start_gate_contract_binding"]
                ),
                "replacement_contract_binding": {},
                "reason_code": "SYNTHETIC_REVIEWED_SUCCESSOR",
            },
            "start_gate_runner_binding": {},
            "transition_control_review_binding": {},
            "repository_context_reanchor": {
                "before": {},
                "after": repository_after,
            },
            "authorization_binding": {},
            "claim_boundary": copy.deepcopy(
                continuation.NPC_SINGLE_ADMIN_RECOVERY_REANCHOR_CLAIM_BOUNDARY
            ),
            "unchanged_control_projection": {},
            "canonical_binding_snapshot_after": copy.deepcopy(
                ready["canonical_binding_snapshot_after"]
            ),
            "noncredit_successor_edges": copy.deepcopy(
                WalkSafeFp048R002Seq90Seq91BoundaryTest.EDGES
            ),
            "previous_event_sha256": continuation.FP048_R002_READY_EVENT_SHA256,
        }
        event["event_sha256"] = continuation.event_sha256(event)
        history.append(event)
        return checkpoint, event

    @staticmethod
    def _seq91_event(reanchor: dict[str, object]) -> dict[str, object]:
        event = {
            "sequence": continuation.FP048_R002_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "occurred_on": "2026-08-26",
            "occurred_at": "2026-08-26T02:10:00+09:00",
            "previous_focus_goal_id": continuation.FP048_R002_GOAL_ID,
            "previous_focus_content_sha256": continuation.FP048_R002_GOAL_SHA256,
            "focus_goal_id": continuation.FP048_R002_GOAL_ID,
            "focus_goal_content_sha256": continuation.FP048_R002_GOAL_SHA256,
            "subject_goal_id": continuation.FP048_R002_GOAL_ID,
            "from_status": "READY",
            "to_status": "IN_PROGRESS",
            "static_plan_manifest_sha256": reanchor[
                "static_plan_manifest_sha256"
            ],
            "status_changes": {
                continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"
            },
            "runtime_after": copy.deepcopy(reanchor["runtime_after"]),
            "repository_snapshot_before": {
                "branch": "current",
                "checkpoint_base_head": "a" * 40,
                "head_commit": "b" * 40,
                "checkpoint_managed_path_count": 7,
                "checkpoint_path_set_sha256": "c" * 64,
                "checkpoint_content_set_sha256": "d" * 64,
            },
            "implementation_start_gate_binding": {},
            "blockers_after": copy.deepcopy(reanchor["blockers_after"]),
            "blocker_resolution_ids_after": copy.deepcopy(
                reanchor["blocker_resolution_ids_after"]
            ),
            "source_checkpoint_version": "1.25.0",
            "evidence_refs": [],
            "previous_event_sha256": reanchor["event_sha256"],
        }
        event["event_sha256"] = continuation.event_sha256(event)
        return event

    @staticmethod
    def _authority() -> mock.Mock:
        authority = mock.Mock()
        authority.require_control_reanchored_checkpoint.return_value = None
        authority.validated_noncredit_successor_edges.return_value = (
            copy.deepcopy(WalkSafeFp048R002Seq90Seq91BoundaryTest.EDGES)
        )
        return authority

    def test_seq90_routes_to_exact_reanchor_and_reviewed_successor_authority(
        self,
    ) -> None:
        checkpoint, event = self._seq90_checkpoint()
        authority = self._authority()
        with mock.patch.object(
            continuation,
            "_fp048_r002_reanchor_authority",
            return_value=authority,
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=checkpoint["goal_execution"]["transition_history"],
            )

        self.assertEqual(errors, [])
        authority.require_control_reanchored_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        authority.validated_noncredit_successor_edges.assert_called_once_with(
            ROOT,
            checkpoint,
        )

    def test_seq90_fails_closed_on_missing_authority_or_edge_drift(self) -> None:
        checkpoint, event = self._seq90_checkpoint()
        with mock.patch.object(
            continuation,
            "_fp048_r002_reanchor_authority",
            side_effect=RuntimeError("synthetic authority missing"),
        ):
            errors = continuation._validate_fp048_r002_control_reanchor_seq90(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=checkpoint["goal_execution"]["transition_history"],
            )
        self.assertIn("authority differs", "\n".join(errors))

        authority = self._authority()
        authority.validated_noncredit_successor_edges.return_value = {
            "modified": [],
            "added": [{"path": "forged"}],
        }
        with mock.patch.object(
            continuation,
            "_fp048_r002_reanchor_authority",
            return_value=authority,
        ):
            errors = continuation._validate_fp048_r002_control_reanchor_seq90(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=checkpoint["goal_execution"]["transition_history"],
            )
        self.assertIn("reviewed noncredit successor edges", "\n".join(errors))

    def test_seq90_repository_after_uses_exact_seq91_gate_snapshot(self) -> None:
        checkpoint, reanchor = self._seq90_checkpoint()
        started = self._seq91_event(reanchor)
        checkpoint["goal_execution"]["transition_history"].append(started)

        self.assertEqual(
            continuation._fp048_r002_reanchor_repository_after(
                checkpoint,
                checkpoint["goal_execution"]["transition_history"],
            ),
            {
                "branch": "current",
                "base_commit": "a" * 40,
                "current_head": "b" * 40,
                "managed_changed_path_count": 7,
                "path_set_sha256": "c" * 64,
                "content_set_sha256": "d" * 64,
            },
        )

    def test_seq91_loads_only_exact_reviewed_r002_five_check_contract(
        self,
    ) -> None:
        checkpoint, reanchor = self._seq90_checkpoint()
        started = self._seq91_event(reanchor)
        checkpoint["goal_execution"]["transition_history"].append(started)
        contract = {
            "schema_version": "1.1",
            "document_id": "WS-FP048-R002-START-R002-TEST",
            "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R002",
            "contract_version": "2026-08-26.1",
            "target_goal_id": continuation.FP048_R002_GOAL_ID,
            "target_goal_content_sha256": continuation.FP048_R002_GOAL_SHA256,
            "gate_purpose": "INITIAL_START",
            "ordered_checks": [
                {"check_id": check_id, "command": "true"}
                for check_id in continuation.FP048_R002_START_GATE_CHECK_IDS
            ],
            "claim_boundary": {"start_gate_status": "NOT_RUN"},
            "successor_reason_code": "SYNTHETIC_REVIEWED_SUCCESSOR",
            "supersedes": {"source_ready_event_sequence": 89},
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            path = root / continuation.FP048_R002_START_GATE_CONTRACT_PATH
            path.parent.mkdir(parents=True)
            raw = json.dumps(contract, ensure_ascii=False, indent=2).encode() + b"\n"
            path.write_bytes(raw)
            binding = {
                "schema_version": "1.1",
                "document_id": contract["document_id"],
                "path": continuation.FP048_R002_START_GATE_CONTRACT_PATH,
                "file_sha256": sha256_bytes(raw),
                "contract_id": contract["contract_id"],
                "contract_version": contract["contract_version"],
                "canonical_contract_sha256": continuation.canonical_json_sha256(
                    contract
                ),
            }
            reanchor["contract_supersession"][
                "replacement_contract_binding"
            ] = binding
            reanchor["event_sha256"] = continuation.event_sha256(reanchor)
            started["previous_event_sha256"] = reanchor["event_sha256"]
            started["event_sha256"] = continuation.event_sha256(started)
            authority = self._authority()
            authority.load_r002_contract.return_value = (contract, binding)
            with mock.patch.object(
                continuation,
                "_fp048_r002_reanchor_authority",
                return_value=authority,
            ):
                errors, checks, actual_binding, ready = (
                    continuation._fp048_r002_start_gate_contract(
                        root,
                        event=started,
                        checkpoint=checkpoint,
                    )
                )

        self.assertEqual(errors, [])
        self.assertEqual(
            [item["check_id"] for item in checks],
            continuation.FP048_R002_START_GATE_CHECK_IDS,
        )
        self.assertEqual(actual_binding, binding)
        self.assertEqual(ready["event_sha256"], continuation.FP048_R002_READY_EVENT_SHA256)

    def test_seq91_private_gate_uses_fresh_event_namespace(self) -> None:
        expected_path = (
            "docs/control/execution/goal-gates/"
            f"{continuation.FP048_R002_STARTED_EVENT_ID}/"
            "implementation-start-gate-receipt.json"
        )
        event = {
            "event_id": continuation.FP048_R002_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "subject_goal_id": continuation.FP048_R002_GOAL_ID,
            "implementation_start_gate_binding": {
                "document_id": "WS-FP048-R002-START-GATE-RECEIPT-TEST",
                "path": expected_path,
                "file_sha256": "a" * 64,
            },
        }
        with mock.patch.object(
            continuation,
            "_load_fp008_private_binding",
            return_value=(["fresh receipt path probe"], {}, None, None),
        ) as loader:
            errors = continuation._validate_start_gate(
                ROOT,
                event=event,
                checkpoint={},
                goal_paths={},
                manifest={},
                manifest_sha256="b" * 64,
                activation_sha256="c" * 64,
                activation_occurred_at=None,
            )

        self.assertEqual(errors, ["fresh receipt path probe"])
        self.assertEqual(loader.call_args.kwargs["expected_path"], expected_path)
        self.assertEqual(
            continuation._validate_fp048_r002_runtime_bindings(
                ROOT,
                [],
                event_id=continuation.FP048_R002_STARTED_EVENT_ID,
            ),
            [],
        )
        self.assertEqual(len(continuation.FP048_R002_START_GATE_CHECK_IDS), 5)
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            event_dir = (
                root
                / continuation.FP008_GATE_ROOT_RELATIVE
                / continuation.FP048_R002_STARTED_EVENT_ID
            )
            event_dir.mkdir(parents=True)
            os.chmod(event_dir, 0o700)
            names = {"implementation-start-gate-receipt.json"}
            names.update(
                f"{index:02d}-{check_id}.log"
                for index, check_id in enumerate(
                    continuation.FP048_R002_START_GATE_CHECK_IDS,
                    start=1,
                )
            )
            for name in names:
                target = event_dir / name
                target.write_bytes(b"test\n")
                os.chmod(target, 0o600)
            metadata = event_dir.stat()
            inventory_errors = continuation._validate_fp008_gate_event_inventory(
                root,
                event_id=continuation.FP048_R002_STARTED_EVENT_ID,
                expected_checks=[
                    {"check_id": check_id, "command": "true"}
                    for check_id in continuation.FP048_R002_START_GATE_CHECK_IDS
                ],
                expected_directory_identity=(metadata.st_dev, metadata.st_ino),
                label="FP048 R002 synthetic private five-check gate",
            )
        self.assertEqual(inventory_errors, [])


class WalkSafeFp048R002Seq91Seq92CorrectionBoundaryTest(unittest.TestCase):
    @staticmethod
    def _checkpoint() -> tuple[dict[str, object], dict[str, object], mock.Mock]:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        event = history[90]
        assert event["sequence"] == continuation.FP048_R002_CONTROL_CORRECTION_SEQUENCE
        assert event["event_id"] == continuation.FP048_R002_CONTROL_CORRECTION_EVENT_ID
        assert event["event_sha256"] == continuation.event_sha256(event)
        history[:] = history[:91]
        state["transition_history_anchor_sha256"] = event["event_sha256"]
        state["validation_cutoff_at"] = event["occurred_at"]
        state["status_by_goal"][continuation.FP048_R002_GOAL_ID] = "READY"
        state["goal_status"] = "READY"
        checkpoint["current_work"]["status"] = "READY"
        repository_after = event["repository_context_reanchor"]["after"]
        snapshot = checkpoint["working_tree_snapshot"]
        snapshot.update(
            {
                "managed_changed_path_count": repository_after[
                    "managed_changed_path_count"
                ],
                "path_set_sha256": repository_after["path_set_sha256"],
                "content_set_sha256": repository_after["content_set_sha256"],
            }
        )
        mirror = checkpoint["session_handoff"]["source_commit_or_snapshot"]
        mirror.update(
            {
                "base_commit": repository_after["base_commit"],
                "current_head": repository_after["current_head"],
            }
        )
        authority = mock.Mock()
        authority.require_control_corrected_checkpoint.return_value = None
        return checkpoint, event, authority

    @staticmethod
    def _seq92_event(correction_event: dict[str, object]) -> dict[str, object]:
        event = WalkSafeFp048R002Seq90Seq91BoundaryTest._seq91_event(
            correction_event
        )
        event["sequence"] = continuation.FP048_R002_CORRECTED_STARTED_SEQUENCE
        event["event_id"] = continuation.FP048_R002_CORRECTED_STARTED_EVENT_ID
        event["previous_event_sha256"] = correction_event["event_sha256"]
        event["event_sha256"] = continuation.event_sha256(event)
        return event

    def test_seq91_dispatches_exact_ready_to_ready_zero_credit_authority(
        self,
    ) -> None:
        checkpoint, event, authority = self._checkpoint()
        with mock.patch.object(
            continuation,
            "_fp048_r002_correction_authority",
            return_value=authority,
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=checkpoint["goal_execution"]["transition_history"],
            )

        self.assertEqual(errors, [])
        authority.require_control_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
        )

    def test_seq91_rejects_status_credit_and_reason_drift(self) -> None:
        for field, value in (
            ("to_status", "IN_PROGRESS"),
            ("status_changes", {continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"}),
            ("correction_reason", {}),
        ):
            with self.subTest(field=field):
                checkpoint, event, authority = self._checkpoint()
                event[field] = value
                event["event_sha256"] = continuation.event_sha256(event)
                with mock.patch.object(
                    continuation,
                    "_fp048_r002_correction_authority",
                    return_value=authority,
                ):
                    errors = continuation._validate_fp048_r002_control_correction_seq91(
                        ROOT,
                        event=event,
                        checkpoint=checkpoint,
                        history=checkpoint["goal_execution"]["transition_history"],
                    )
                self.assertTrue(errors)

    def test_seq90_repository_binding_remains_frozen_after_seq91(self) -> None:
        checkpoint, _event, _authority = self._checkpoint()
        history = checkpoint["goal_execution"]["transition_history"]
        expected = history[89]["repository_context_reanchor"]["after"]
        self.assertEqual(
            continuation._fp048_r002_reanchor_repository_after(
                checkpoint,
                history,
            ),
            expected,
        )

    def test_seq91_repository_after_uses_only_exact_seq92_snapshot(self) -> None:
        checkpoint, correction_event, _authority = self._checkpoint()
        started = self._seq92_event(correction_event)
        checkpoint["goal_execution"]["transition_history"].append(started)
        self.assertEqual(
            continuation._fp048_r002_correction_repository_after(
                checkpoint,
                checkpoint["goal_execution"]["transition_history"],
            ),
            {
                "branch": "current",
                "base_commit": "a" * 40,
                "current_head": "b" * 40,
                "managed_changed_path_count": 7,
                "path_set_sha256": "c" * 64,
                "content_set_sha256": "d" * 64,
            },
        )

    def test_seq92_gate_contract_fails_closed_on_inverse_delta_authority_error(
        self,
    ) -> None:
        checkpoint, correction_event, authority = self._checkpoint()
        started = self._seq92_event(correction_event)
        checkpoint["goal_execution"]["transition_history"].append(started)
        authority.require_control_corrected_checkpoint.side_effect = RuntimeError(
            "seq92 pre-overwrite inverse-delta state differs"
        )
        with mock.patch.object(
            continuation,
            "_fp048_r002_correction_authority",
            return_value=authority,
        ):
            errors, _checks, _binding, _ready = (
                continuation._fp048_r002_r003_start_gate_contract(
                    ROOT,
                    event=started,
                    checkpoint=checkpoint,
                )
            )
        self.assertTrue(
            any("correction authority differs" in error for error in errors)
        )

    def test_only_new_seq92_start_id_is_reserved(self) -> None:
        self.assertEqual(
            continuation.FP048_R002_CORRECTED_STARTED_SEQUENCE,
            92,
        )
        self.assertEqual(
            continuation.FP048_R002_CORRECTED_STARTED_EVENT_ID,
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-002",
        )
        self.assertEqual(
            [93, 94],
            [
                continuation.FP048_R002_CORRECTED_STARTED_SEQUENCE + 1,
                continuation.FP048_R002_CORRECTED_STARTED_SEQUENCE + 2,
            ],
        )


class WalkSafeFp048R002Seq92Seq93R004BoundaryTest(unittest.TestCase):
    @staticmethod
    def _checkpoint() -> tuple[
        dict[str, object],
        dict[str, object],
        bytes,
        dict[str, object],
        mock.Mock,
        mock.Mock,
    ]:
        from scripts import (
            apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826
            as seq91,
        )
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826
            as seq92,
        )

        live_raw = CHECKPOINT.read_bytes()
        live = json.loads(live_raw)
        live_history = live["goal_execution"]["transition_history"]
        seq94_checkpoint = None
        if (
            len(live_history) == continuation.FP048_R002_R007_STARTED_SEQUENCE
            and live_history[-1].get("event_id")
            == continuation.FP048_R002_R007_STARTED_EVENT_ID
        ):
            from scripts import (
                apply_walksafe_fp048_r002_goal_started_seq96_20260826 as seq96,
            )
            from scripts import (
                apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826
                as seq95,
            )

            seq95_checkpoint = json.loads(
                seq96.reconstructed_seq95_checkpoint_bytes(ROOT, live)
            )
            seq94_checkpoint = seq95._restored_seq94_checkpoint(seq95_checkpoint)
        elif (
            len(live_history)
            == continuation.FP048_R002_R007_CONTRACT_CORRECTION_SEQUENCE
            and live_history[-1].get("event_id")
            == continuation.FP048_R002_R007_CONTRACT_CORRECTION_EVENT_ID
        ):
            from scripts import (
                apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826
                as seq95,
            )

            seq94_checkpoint = seq95._restored_seq94_checkpoint(live)
        elif (
            len(live_history)
            == continuation.FP048_R002_R006_CONTRACT_CORRECTION_SEQUENCE
            and live_history[-1].get("event_id")
            == continuation.FP048_R002_R006_CONTRACT_CORRECTION_EVENT_ID
        ):
            seq94_checkpoint = live
        if seq94_checkpoint is not None:
            from scripts import (
                apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826
                as seq94,
            )

            seq93_checkpoint = seq94._restored_seq93_checkpoint(seq94_checkpoint)
        elif (
            len(live_history)
            == continuation.FP048_R002_BRANCH_SEMANTICS_REANCHOR_SEQUENCE
            and live_history[92].get("event_id")
            == continuation.FP048_R002_BRANCH_SEMANTICS_REANCHOR_EVENT_ID
        ):
            seq93_checkpoint = live
        else:
            seq93_checkpoint = None
        if seq93_checkpoint is not None:
            from scripts import (
                apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826
                as seq93,
            )

            seq92_checkpoint = seq93._restored_seq92_checkpoint(
                seq93_checkpoint
            )
        elif len(live_history) == seq92.CORRECTION_SEQUENCE:
            seq92_checkpoint = live
        elif (
            len(live_history) == continuation.FP048_R002_R004_STARTED_SEQUENCE
            and live_history[-1].get("event_id")
            == continuation.FP048_R002_R004_STARTED_EVENT_ID
        ):
            from scripts import (
                apply_walksafe_fp048_r002_goal_started_seq93_20260826
                as legacy_seq93,
            )

            seq92_checkpoint = json.loads(
                legacy_seq93.reconstructed_seq92_checkpoint_bytes(ROOT, live)
            )
        else:
            seq92_checkpoint = None
        if seq92_checkpoint is not None:
            raw = seq92.checkpoint_json_bytes(
                seq92._restored_seq91_checkpoint(seq92_checkpoint)
            )
            source = json.loads(raw)
        else:
            raw = live_raw
            source = live
        paths = source["working_tree_snapshot"]["managed_changed_paths"]
        checkpoint, event = seq92.project_seq92(
            ROOT,
            source,
            managed_paths=paths,
            path_set_sha256=source["working_tree_snapshot"][
                "path_set_sha256"
            ],
            content_set_sha256=source["working_tree_snapshot"][
                "content_set_sha256"
            ],
            occurred_at="2026-08-26T10:00:00+09:00",
            authorization_binding_value={
                "path": "synthetic-authorization",
                "sha256": "a" * 64,
                "byte_length": 1,
            },
            review_binding={
                role: {
                    "path": f"synthetic-{role}",
                    "sha256": "b" * 64,
                    "byte_length": 1,
                }
                for role in ("assignment", "review_result", "independent_review")
            },
            replacement_contract_binding=seq92.r004_contract_binding(ROOT),
            replacement_runner_binding=seq92.r004_runner_binding(ROOT),
        )
        seq92_authority = mock.Mock()
        seq92_authority.load_exact_seq91_source.return_value = (raw, source)
        seq92_authority.reconstructed_seq91_checkpoint_bytes.return_value = raw
        seq92_authority.r004_contract_binding.return_value = (
            seq92.r004_contract_binding(ROOT)
        )
        seq92_authority.require_contract_corrected_checkpoint.return_value = None
        seq91_authority = mock.Mock()
        seq91_authority.r003_contract_binding.return_value = (
            seq91.r003_contract_binding(ROOT)
        )
        return (
            checkpoint,
            event,
            raw,
            source,
            seq92_authority,
            seq91_authority,
        )

    @staticmethod
    def _seq93_event(contract_event: dict[str, object]) -> dict[str, object]:
        event = WalkSafeFp048R002Seq90Seq91BoundaryTest._seq91_event(
            contract_event
        )
        event.update(
            {
                "sequence": continuation.FP048_R002_R004_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
                "occurred_at": "2026-08-26T10:10:00+09:00",
                "previous_event_sha256": contract_event["event_sha256"],
            }
        )
        event["event_sha256"] = continuation.event_sha256(event)
        return event

    def test_seq92_dispatches_exact_ready_to_ready_r003_to_r004_authority(
        self,
    ) -> None:
        checkpoint, event, _raw, _source, seq92_authority, seq91_authority = (
            self._checkpoint()
        )
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_contract_correction_authority",
                return_value=seq92_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_correction_authority",
                return_value=seq91_authority,
            ),
            mock.patch.object(
                continuation,
                "_require_fp048_r002_exact_seq92_source_authority",
                return_value=True,
            ) as exact_source_authority,
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=checkpoint["goal_execution"]["transition_history"],
            )

        self.assertEqual(errors, [])
        exact_source_authority.assert_called_once_with(
            ROOT,
            checkpoint,
            checkpoint["goal_execution"]["transition_history"],
        )
        seq92_authority.require_contract_corrected_checkpoint.assert_not_called()
        seq92_authority.reconstructed_seq91_checkpoint_bytes.assert_not_called()
        seq92_authority.load_exact_seq91_source.assert_not_called()

    def test_seq92_rejects_status_source_cas_and_supersession_drift(self) -> None:
        for field, value in (
            ("to_status", "IN_PROGRESS"),
            ("previous_event_sha256", "0" * 64),
            ("contract_supersession", {}),
        ):
            with self.subTest(field=field):
                (
                    checkpoint,
                    event,
                    _raw,
                    _source,
                    seq92_authority,
                    seq91_authority,
                ) = self._checkpoint()
                event[field] = value
                event["event_sha256"] = continuation.event_sha256(event)
                with (
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_contract_correction_authority",
                        return_value=seq92_authority,
                    ),
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_correction_authority",
                        return_value=seq91_authority,
                    ),
                    mock.patch.object(
                        continuation,
                        "_require_fp048_r002_exact_seq92_source_authority",
                        return_value=True,
                    ),
                ):
                    errors = (
                        continuation._validate_fp048_r002_start_gate_contract_correction_seq92(
                            ROOT,
                            event=event,
                            checkpoint=checkpoint,
                            history=checkpoint["goal_execution"][
                                "transition_history"
                            ],
                        )
                    )
                self.assertTrue(errors)

    def test_seq93_selects_r004_contract_and_exact_started_authority(self) -> None:
        checkpoint, correction_event, _raw, _source, seq92_authority, seq91_authority = (
            self._checkpoint()
        )
        started = self._seq93_event(correction_event)
        checkpoint["goal_execution"]["transition_history"].append(started)
        started_authority = mock.Mock()
        started_authority.require_exact_seq93_projection.return_value = None
        started_authority.goal_start_gate_receipt_binding.return_value = {}
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_contract_correction_authority",
                return_value=seq92_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_correction_authority",
                return_value=seq91_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r004_started_authority",
                return_value=started_authority,
            ),
        ):
            errors, checks, binding, ready = (
                continuation._fp048_r002_start_gate_contract(
                    ROOT,
                    event=started,
                    checkpoint=checkpoint,
                )
            )

        self.assertEqual(errors, [])
        self.assertEqual(
            [row["check_id"] for row in checks],
            continuation.FP048_R002_START_GATE_CHECK_IDS,
        )
        self.assertEqual(
            binding["contract_id"],
            continuation.FP048_R002_R004_START_GATE_CONTRACT_ID,
        )
        self.assertEqual(
            ready["event_sha256"],
            continuation.FP048_R002_READY_EVENT_SHA256,
        )
        started_authority.require_exact_seq93_projection.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=True,
        )

    def test_seq92_descendant_keeps_logical_branch_not_physical_branch(self) -> None:
        correction_event = {
            "event_sha256": "e" * 64,
        }
        started = {
            "sequence": continuation.FP048_R002_R004_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
            "subject_goal_id": continuation.FP048_R002_GOAL_ID,
            "previous_event_sha256": correction_event["event_sha256"],
            "repository_snapshot_before": {
                "branch": "recovery/fp046-r008-wip-20260822",
                "checkpoint_base_head": "a" * 40,
                "head_commit": "b" * 40,
                "checkpoint_managed_path_count": 7,
                "checkpoint_path_set_sha256": "c" * 64,
                "checkpoint_content_set_sha256": "d" * 64,
            },
        }
        history = [{} for _ in range(91)] + [correction_event, started]
        checkpoint = {
            "session_handoff": {"branch": "current"},
        }

        restored = continuation._fp048_r002_contract_correction_repository_after(
            checkpoint,
            history,
        )

        self.assertEqual(
            restored,
            {
                "branch": "current",
                "base_commit": "a" * 40,
                "current_head": "b" * 40,
                "managed_changed_path_count": 7,
                "path_set_sha256": "c" * 64,
                "content_set_sha256": "d" * 64,
            },
        )

    def test_new_contract_correction_has_zero_credit_generic_grammar(self) -> None:
        statuses = {continuation.FP048_R002_GOAL_ID: "READY"}
        event = {"subject_goal_id": continuation.FP048_R002_GOAL_ID}
        self.assertIn(
            "GOAL_START_GATE_CONTRACT_CORRECTED",
            continuation.ALLOWED_EVENT_TYPES,
        )
        self.assertEqual(
            continuation._expected_status_change(
                "GOAL_START_GATE_CONTRACT_CORRECTED",
                event,
                statuses,
            ),
            ({}, None),
        )
        self.assertEqual(
            continuation._expected_from_to(
                "GOAL_START_GATE_CONTRACT_CORRECTED",
                event,
                statuses,
                {},
            ),
            ("READY", "READY"),
        )


class WalkSafeFp048R002Seq93Seq94R005BoundaryTest(unittest.TestCase):
    @staticmethod
    def _synthetic_reanchor() -> tuple[
        dict[str, object],
        dict[str, object],
        list[dict[str, object]],
        mock.Mock,
    ]:
        live = continuation.load_json(CHECKPOINT)
        source = copy.deepcopy(
            live["goal_execution"]["transition_history"][91]
        )
        passed_attempt = {
            "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
            "directory": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R004_STARTED_EVENT_ID}"
            ),
            "directory_mode": "0700",
            "status": "PASS_UNCONSUMED",
            "files": [],
        }
        replacement = {
            "schema_version": "1.2",
            "document_id": continuation.FP048_R002_R005_START_GATE_DOCUMENT_ID,
            "path": continuation.FP048_R002_R005_START_GATE_CONTRACT_PATH,
            "file_sha256": "a" * 64,
            "contract_id": continuation.FP048_R002_R005_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R005_START_GATE_CONTRACT_VERSION
            ),
            "canonical_contract_sha256": "b" * 64,
        }
        event = copy.deepcopy(source)
        event.update(
            {
                "sequence": (
                    continuation.FP048_R002_BRANCH_SEMANTICS_REANCHOR_SEQUENCE
                ),
                "event_id": (
                    continuation.FP048_R002_BRANCH_SEMANTICS_REANCHOR_EVENT_ID
                ),
                "event_type": "GOAL_START_CONTROL_REANCHORED",
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "previous_event_sha256": source["event_sha256"],
                "source_checkpoint_binding": {
                    **copy.deepcopy(source["source_checkpoint_binding"]),
                    "passed_gate_attempt_003": copy.deepcopy(passed_attempt),
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(
                        source["contract_supersession"][
                            "replacement_contract_binding"
                        ]
                    ),
                    "reason_code": (
                        "SEQ93_BRANCH_SEMANTICS_REANCHOR_AND_FRESH_GATE_REQUIRED"
                    ),
                    "replacement_contract_binding": copy.deepcopy(replacement),
                },
            }
        )
        event["repository_context_reanchor"]["after"].update(
            {
                "branch": "current",
                "logical_branch": "current",
                "logical_branch_semantics": "WORKSTREAM_LABEL",
                "physical_git_branch": "recovery/fp046-r008-wip-20260822",
                "branch_mismatch_reason_code": (
                    "LOGICAL_CURRENT_LABEL_IS_NOT_PHYSICAL_GIT_BRANCH"
                ),
            }
        )
        event["event_sha256"] = continuation.event_sha256(event)
        history = [copy.deepcopy(source) for _ in range(92)] + [event]
        checkpoint = {
            "approved_state": copy.deepcopy(live["approved_state"]),
            "session_handoff": {"branch": "current"},
            "verification_boundary": copy.deepcopy(
                live["verification_boundary"]
            ),
        }
        authority = mock.Mock()
        authority.EVENT_FIELDS = frozenset(event)
        authority.CLAIM_BOUNDARY = copy.deepcopy(event["claim_boundary"])
        authority.LOGICAL_BRANCH = "current"
        authority.LOGICAL_BRANCH_SEMANTICS = "WORKSTREAM_LABEL"
        authority.PHYSICAL_GIT_BRANCH = "recovery/fp046-r008-wip-20260822"
        authority.BRANCH_MISMATCH_REASON_CODE = (
            "LOGICAL_CURRENT_LABEL_IS_NOT_PHYSICAL_GIT_BRANCH"
        )
        authority.R005_SUCCESSOR_REASON_CODE = (
            "SEQ93_BRANCH_SEMANTICS_REANCHOR_AND_FRESH_GATE_REQUIRED"
        )
        authority.passed_gate_attempt_003_binding.return_value = passed_attempt
        authority.r005_contract_binding.return_value = replacement
        authority.require_branch_semantics_reanchored_checkpoint.return_value = None
        return checkpoint, event, history, authority

    def test_seq93_dispatches_exact_ready_zero_credit_branch_authority(
        self,
    ) -> None:
        checkpoint, event, history, authority = self._synthetic_reanchor()
        frozen = mock.Mock()
        frozen.reconstructed_seq93_checkpoint_bytes.return_value = json.dumps(
            {"goal_execution": {"transition_history": history}}
        ).encode("utf-8")
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_branch_semantics_reanchor_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_contract_correction_authority",
                return_value=frozen,
            ),
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertEqual(errors, [])
        frozen.reconstructed_seq93_checkpoint_bytes.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        authority.require_branch_semantics_reanchored_checkpoint.assert_not_called()

    def test_seq93_rejects_consumed_attempt_and_r004_r005_drift(self) -> None:
        for field in ("passed_gate_attempt_003", "contract_supersession"):
            with self.subTest(field=field):
                checkpoint, event, history, authority = self._synthetic_reanchor()
                if field == "passed_gate_attempt_003":
                    event["source_checkpoint_binding"][field]["status"] = "PASS"
                else:
                    event[field]["replacement_contract_binding"] = {}
                event["event_sha256"] = continuation.event_sha256(event)
                with mock.patch.object(
                    continuation,
                    "_fp048_r002_branch_semantics_reanchor_authority",
                    return_value=authority,
                ):
                    errors = (
                        continuation._validate_fp048_r002_branch_semantics_reanchor_seq93(
                            ROOT,
                            event=event,
                            checkpoint=checkpoint,
                            history=history,
                        )
                    )
                self.assertTrue(errors)

    def test_direct_seq94_r005_start_is_nonauthoritative(self) -> None:
        checkpoint = {"goal_execution": {"transition_history": []}}
        event = {
            "sequence": continuation.FP048_R002_R005_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R005_STARTED_EVENT_ID,
        }
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r005_start_gate_contract",
            ) as retired,
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_start_gate_contract",
            ) as successor,
        ):
            actual = continuation._fp048_r002_start_gate_contract(
                ROOT,
                event=event,
                checkpoint=checkpoint,
            )

        self.assertTrue(
            any("direct pre-seq97 -004 start is nonauthoritative" in error for error in actual[0])
        )
        self.assertEqual(actual[1:], ([], {}, {}))
        retired.assert_not_called()
        successor.assert_not_called()

    def test_seq94_descendant_requires_contract_correction(self) -> None:
        checkpoint, reanchor, history, reanchor_authority = (
            self._synthetic_reanchor()
        )
        started = {
            "sequence": continuation.FP048_R002_R005_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R005_STARTED_EVENT_ID,
        }
        history.append(started)
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_branch_semantics_reanchor_authority",
                return_value=reanchor_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r005_started_authority",
            ) as retired,
        ):
            with self.assertRaisesRegex(ValueError, "exact R005-to-R006 correction"):
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    history,
                )

        retired.assert_not_called()
        reanchor_authority.require_branch_semantics_reanchored_checkpoint.assert_not_called()

    def test_pass_unconsumed_seq93_cannot_fall_back_to_legacy_r004(
        self,
    ) -> None:
        checkpoint, event, history, _authority = self._synthetic_reanchor()
        event.update(
            {
                "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "to_status": "IN_PROGRESS",
                "status_changes": {
                    continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"
                },
            }
        )
        event["event_sha256"] = continuation.event_sha256(event)
        checkpoint["goal_execution"] = {"transition_history": history}

        with self.assertRaisesRegex(ValueError, "cannot fall back"):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                history,
            )

        with mock.patch.object(
            continuation,
            "_fp048_r002_r004_start_gate_contract",
        ) as legacy_gate:
            errors, checks, binding, ready = (
                continuation._fp048_r002_start_gate_contract(
                    ROOT,
                    event=event,
                    checkpoint=checkpoint,
                )
            )
        self.assertTrue(
            any("cannot dispatch legacy R004 -003" in error for error in errors)
        )
        self.assertEqual((checks, binding, ready), ([], {}, {}))
        legacy_gate.assert_not_called()

        direct_errors, direct_checks, direct_binding, direct_ready = (
            continuation._fp048_r002_r004_start_gate_contract(
                ROOT,
                event=event,
                checkpoint=checkpoint,
            )
        )
        self.assertTrue(
            any(
                "cannot dispatch legacy R004 -003" in error
                for error in direct_errors
            )
        )
        self.assertEqual(
            (direct_checks, direct_binding, direct_ready),
            ([], {}, {}),
        )

    def test_genuine_legacy_seq93_still_dispatches_r004(self) -> None:
        event = {
            "sequence": continuation.FP048_R002_R004_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
        }
        checkpoint = {
            "goal_execution": {
                "transition_history": [{} for _ in range(92)] + [event]
            }
        }
        expected = ([], [], {"contract_id": "R004"}, {})
        with mock.patch.object(
            continuation,
            "_fp048_r002_r004_start_gate_contract",
            return_value=expected,
        ) as legacy_gate:
            actual = continuation._fp048_r002_start_gate_contract(
                ROOT,
                event=event,
                checkpoint=checkpoint,
            )

        self.assertEqual(actual, expected)
        legacy_gate.assert_called_once_with(
            ROOT,
            event=event,
            checkpoint=checkpoint,
        )

    def test_seq92_validator_rejects_marked_legacy_fallback(self) -> None:
        (
            checkpoint,
            event,
            _raw,
            _source,
            seq92_authority,
            seq91_authority,
        ) = WalkSafeFp048R002Seq92Seq93R004BoundaryTest._checkpoint()
        fallback = copy.deepcopy(event)
        fallback.update(
            {
                "sequence": continuation.FP048_R002_R004_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "previous_event_sha256": event["event_sha256"],
            }
        )
        fallback["source_checkpoint_binding"]["passed_gate_attempt_003"] = {
            "event_id": continuation.FP048_R002_R004_STARTED_EVENT_ID,
            "status": "PASS_UNCONSUMED",
        }
        fallback["event_sha256"] = continuation.event_sha256(fallback)
        checkpoint["goal_execution"]["transition_history"].append(fallback)

        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_contract_correction_authority",
                return_value=seq92_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_correction_authority",
                return_value=seq91_authority,
            ),
            mock.patch.object(
                continuation,
                "_require_fp048_r002_exact_seq92_source_authority",
                return_value=False,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r004_started_authority",
            ) as legacy_authority,
        ):
            errors = (
                continuation._validate_fp048_r002_start_gate_contract_correction_seq92(
                    ROOT,
                    event=event,
                    checkpoint=checkpoint,
                    history=checkpoint["goal_execution"][
                        "transition_history"
                    ],
                )
            )

        self.assertTrue(
            any("cannot fall back to legacy R004 -003" in error for error in errors)
        )
        legacy_authority.assert_not_called()

    def test_seq92_historical_validation_uses_seq93_frozen_overlay(self) -> None:
        (
            checkpoint,
            event,
            _raw,
            _source,
            seq92_authority,
            seq91_authority,
        ) = WalkSafeFp048R002Seq92Seq93R004BoundaryTest._checkpoint()
        seq92_checkpoint = copy.deepcopy(checkpoint)
        seq92_raw = json.dumps(
            seq92_checkpoint,
            ensure_ascii=False,
            indent=2,
        ).encode("utf-8") + b"\n"
        branch_event = copy.deepcopy(event)
        branch_event.update(
            {
                "sequence": (
                    continuation.FP048_R002_BRANCH_SEMANTICS_REANCHOR_SEQUENCE
                ),
                "event_id": (
                    continuation.FP048_R002_BRANCH_SEMANTICS_REANCHOR_EVENT_ID
                ),
                "event_type": "GOAL_START_CONTROL_REANCHORED",
                "previous_event_sha256": event["event_sha256"],
                "repository_context_reanchor": {
                    "before": {
                        "current_work": copy.deepcopy(
                            checkpoint["current_work"]
                        ),
                        "working_tree_snapshot": copy.deepcopy(
                            checkpoint["working_tree_snapshot"]
                        ),
                        "session_handoff": copy.deepcopy(
                            checkpoint["session_handoff"]
                        ),
                    },
                    "after": copy.deepcopy(
                        event["repository_context_reanchor"]["after"]
                    ),
                },
            }
        )
        branch_event["event_sha256"] = continuation.event_sha256(
            branch_event
        )
        checkpoint["goal_execution"]["transition_history"].append(
            branch_event
        )
        branch_authority = mock.Mock()
        branch_authority.reconstructed_seq92_checkpoint_bytes.return_value = (
            seq92_raw
        )
        branch_authority.require_branch_semantics_reanchored_checkpoint.return_value = (
            None
        )
        frozen_authority = mock.Mock()
        frozen_authority.reconstructed_seq93_checkpoint_bytes.return_value = (
            json.dumps(
                {
                    "goal_execution": {
                        "transition_history": checkpoint["goal_execution"][
                            "transition_history"
                        ]
                    }
                }
            ).encode("utf-8")
        )
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_contract_correction_authority",
                return_value=seq92_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_correction_authority",
                return_value=seq91_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_branch_semantics_reanchor_authority",
                return_value=branch_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_contract_correction_authority",
                return_value=frozen_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_reconstructed_seq93_descendant",
                return_value=checkpoint,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_reconstructed_seq92_from_frozen_seq93",
                return_value=seq92_raw,
            ) as frozen_inverse,
        ):
            errors = (
                continuation._validate_fp048_r002_start_gate_contract_correction_seq92(
                    ROOT,
                    event=event,
                    checkpoint=checkpoint,
                    history=checkpoint["goal_execution"][
                        "transition_history"
                    ],
                )
            )

        self.assertEqual(errors, [])
        seq92_authority.reconstructed_seq91_checkpoint_bytes.assert_not_called()
        seq92_authority.require_contract_corrected_checkpoint.assert_not_called()
        frozen_inverse.assert_called_once_with(
            ROOT,
            mock.ANY,
        )
        frozen_authority.reconstructed_seq93_checkpoint_bytes.assert_called_once_with(
            ROOT,
            checkpoint,
        )


class WalkSafeFp048R002Seq94Seq95R006BoundaryTest(unittest.TestCase):
    @staticmethod
    def _synthetic_correction() -> tuple[
        dict[str, object],
        dict[str, object],
        list[dict[str, object]],
        mock.Mock,
    ]:
        checkpoint, source, history, _reanchor_authority = (
            WalkSafeFp048R002Seq93Seq94R005BoundaryTest._synthetic_reanchor()
        )
        passed = copy.deepcopy(
            source["source_checkpoint_binding"]["passed_gate_attempt_003"]
        )
        preflight = {
            "event_id": continuation.FP048_R002_R006_STARTED_EVENT_ID,
            "directory": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R006_STARTED_EVENT_ID}"
            ),
            "receipt_path": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R006_STARTED_EVENT_ID}/"
                "implementation-start-gate-receipt.json"
            ),
            "status": "PREFLIGHT_FAILED_NO_GATE_NAMESPACE_CREATED",
            "authority_status": "NONAUTHORITY",
            "event_identity_status": "REUSABLE_UNCONSUMED",
            "namespace_present": False,
            "receipt_present": False,
            "reason_code": "R005_PREVIEW_FAILED_NO_NAMESPACE_NONAUTHORITY",
        }
        previous = copy.deepcopy(
            source["contract_supersession"]["replacement_contract_binding"]
        )
        replacement = {
            "schema_version": "1.2",
            "document_id": continuation.FP048_R002_R006_START_GATE_DOCUMENT_ID,
            "path": continuation.FP048_R002_R006_START_GATE_CONTRACT_PATH,
            "file_sha256": "c" * 64,
            "contract_id": continuation.FP048_R002_R006_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R006_START_GATE_CONTRACT_VERSION
            ),
            "canonical_contract_sha256": "d" * 64,
        }
        runner = {
            "path": "scripts/run_walksafe_fp048_r002_goal_start_gate_r006_20260826.py",
            "sha256": "e" * 64,
            "byte_length": 1,
        }
        reason = {
            "failed_contract_id": continuation.FP048_R002_R005_START_GATE_CONTRACT_ID,
            "failed_contract_version": continuation.FP048_R002_R005_START_GATE_CONTRACT_VERSION,
            "preflight_attempt_004": copy.deepcopy(preflight),
            "reason_code": "R005_PREDECESSOR_LIVE_SOURCE_REGRESSION_NOT_POSTPUBLICATION_SAFE",
            "remediation": "SUPERSEDE_R005_WITH_STAGE_AWARE_R006_BEFORE_SEQ95_START",
        }
        event = copy.deepcopy(source)
        event.update(
            {
                "sequence": continuation.FP048_R002_R006_CONTRACT_CORRECTION_SEQUENCE,
                "event_id": continuation.FP048_R002_R006_CONTRACT_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "previous_event_sha256": source["event_sha256"],
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(source["runtime_after"]),
                "blockers_after": copy.deepcopy(source["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    source["blocker_resolution_ids_after"]
                ),
                "canonical_binding_snapshot_after": copy.deepcopy(
                    source["canonical_binding_snapshot_after"]
                ),
                "source_checkpoint_binding": {
                    **copy.deepcopy(source["source_checkpoint_binding"]),
                    "passed_gate_attempt_003": copy.deepcopy(passed),
                    "preflight_attempt_004": copy.deepcopy(preflight),
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(previous),
                    "reason_code": reason["reason_code"],
                    "replacement_contract_binding": copy.deepcopy(replacement),
                },
                "start_gate_runner_binding": copy.deepcopy(runner),
                "correction_reason": copy.deepcopy(reason),
            }
        )
        event["event_sha256"] = continuation.event_sha256(event)
        history.append(event)
        live = continuation.load_json(CHECKPOINT)
        state = copy.deepcopy(live["goal_execution"])
        state["transition_history"] = history
        state["status_by_goal"][continuation.FP048_R002_GOAL_ID] = "READY"
        state["goal_status"] = "READY"
        checkpoint["goal_execution"] = state
        checkpoint["current_work"] = copy.deepcopy(live["current_work"])
        checkpoint["current_work"]["status"] = "READY"
        checkpoint["current_work"]["release_completion_claimed"] = False
        authority = mock.Mock()
        authority.EVENT_FIELDS = frozenset(event)
        authority.CLAIM_BOUNDARY = copy.deepcopy(event["claim_boundary"])
        authority.CORRECTION_REASON = copy.deepcopy(reason)
        authority.R006_SUCCESSOR_REASON_CODE = reason["reason_code"]
        authority.passed_gate_attempt_003_binding.return_value = passed
        authority.preflight_attempt_004_observation.return_value = preflight
        authority.r005_contract_binding.return_value = previous
        authority.r006_contract_binding.return_value = replacement
        authority.r006_runner_binding.return_value = runner
        authority.require_contract_corrected_checkpoint.return_value = None
        return checkpoint, event, history, authority

    @staticmethod
    def _started_authority() -> mock.Mock:
        authority = mock.Mock()
        authority.reconstructed_seq94_checkpoint_bytes.return_value = b"{}"
        authority.require_exact_seq95_projection.return_value = None
        return authority

    def test_seq94_dispatches_exact_zero_credit_r006_correction(self) -> None:
        checkpoint, event, history, authority = self._synthetic_correction()
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_contract_correction_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_require_fp048_r002_frozen_seq94_source",
            ) as frozen_source,
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertEqual(errors, [])
        frozen_source.assert_called_once_with(ROOT, checkpoint)
        authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq94_rejects_preflight_credit_and_successor_tamper(self) -> None:
        for mutation in ("preflight", "successor", "status", "global_status"):
            with self.subTest(mutation=mutation):
                checkpoint, event, history, authority = self._synthetic_correction()
                if mutation == "preflight":
                    event["source_checkpoint_binding"]["preflight_attempt_004"][
                        "authority_status"
                    ] = "AUTHORITY"
                elif mutation == "successor":
                    event["contract_supersession"][
                        "replacement_contract_binding"
                    ] = {}
                else:
                    if mutation == "status":
                        event["to_status"] = "IN_PROGRESS"
                        event["status_changes"] = {
                            continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"
                        }
                    else:
                        checkpoint["goal_execution"]["status_by_goal"][
                            continuation.FP048_R002_GOAL_ID
                        ] = "IN_PROGRESS"
                        checkpoint["goal_execution"]["goal_status"] = (
                            "IN_PROGRESS"
                        )
                        checkpoint["current_work"]["status"] = "IN_PROGRESS"
                event["event_sha256"] = continuation.event_sha256(event)
                with (
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_r006_contract_correction_authority",
                        return_value=authority,
                    ),
                    mock.patch.object(
                        continuation,
                        "_require_fp048_r002_frozen_seq94_source",
                    ),
                ):
                    errors = continuation._validate_fp048_r002_r006_contract_correction_seq94(
                        ROOT,
                        event=event,
                        checkpoint=checkpoint,
                        history=history,
                    )
                self.assertTrue(errors)

    def test_new_lineage_rejects_float_sequences_wrong_pair_and_extra_tail(
        self,
    ) -> None:
        checkpoint, event, history, correction_authority = (
            self._synthetic_correction()
        )
        event["sequence"] = 94.0
        event["event_sha256"] = continuation.event_sha256(event)
        with mock.patch.object(
            continuation,
            "_fp048_r002_r006_contract_correction_authority",
            return_value=correction_authority,
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )
        self.assertTrue(errors)

        with mock.patch.object(
            continuation,
            "_fp048_r002_r006_start_gate_contract",
        ) as loader:
            errors, checks, binding, ready = (
                continuation._fp048_r002_start_gate_contract(
                    ROOT,
                    event={
                        "sequence": 95.0,
                        "event_id": continuation.FP048_R002_R006_STARTED_EVENT_ID,
                    },
                    checkpoint=checkpoint,
                )
            )
        self.assertTrue(errors)
        self.assertEqual((checks, binding, ready), ([], {}, {}))
        loader.assert_not_called()

        checkpoint, _event, history, correction_authority = (
            self._synthetic_correction()
        )
        history.append(
            {
                "sequence": continuation.FP048_R002_R006_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R006_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        history[93]["event_id"] = continuation.FP048_R002_R005_STARTED_EVENT_ID
        with self.assertRaisesRegex(ValueError, "exact R006-to-R007 correction"):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                history,
            )
        history[93]["event_id"] = (
            continuation.FP048_R002_R006_CONTRACT_CORRECTION_EVENT_ID
        )
        history[94] = {
            "sequence": continuation.FP048_R002_R007_CONTRACT_CORRECTION_SEQUENCE,
            "event_id": continuation.FP048_R002_R007_CONTRACT_CORRECTION_EVENT_ID,
            "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
        }
        history.append(
            {
                "sequence": continuation.FP048_R002_R007_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R007_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        history.append({"sequence": 97, "event_id": "WS-FORGED-TAIL"})
        history.append({"sequence": 98, "event_id": "WS-FORGED-EXTRA-TAIL"})
        with self.assertRaisesRegex(ValueError, "must end exactly"):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                history,
            )

    def test_new_authority_loaders_fail_closed_on_missing_public_inverse_api(
        self,
    ) -> None:
        import scripts as scripts_package

        with mock.patch.object(
            scripts_package,
            "apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826",
            object(),
            create=True,
        ):
            with self.assertRaisesRegex(RuntimeError, "API is missing"):
                continuation._fp048_r002_r006_contract_correction_authority()
        with mock.patch.object(
            scripts_package,
            "apply_walksafe_fp048_r002_goal_started_seq95_20260826",
            object(),
            create=True,
        ):
            with self.assertRaisesRegex(RuntimeError, "nonauthoritative"):
                continuation._fp048_r002_r006_started_authority()

    def test_direct_seq95_started_descendant_is_rejected(self) -> None:
        checkpoint, _event, history, correction_authority = (
            self._synthetic_correction()
        )
        started = {
            "sequence": continuation.FP048_R002_R006_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R006_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
        }
        history.append(started)
        with self.assertRaisesRegex(ValueError, "exact R006-to-R007 correction"):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                history,
            )

    def test_seq95_historical_seq94_validation_does_not_recheck_absent_namespace(
        self,
    ) -> None:
        checkpoint, event, history, correction_authority = (
            self._synthetic_correction()
        )
        history.append(
            {
                "sequence": continuation.FP048_R002_R006_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R006_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        correction_authority.preflight_attempt_004_observation.side_effect = (
            AssertionError("live seq95 namespace must not reopen seq94 absence")
        )
        started_authority = self._started_authority()
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_contract_correction_authority",
                return_value=correction_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_started_authority",
                return_value=started_authority,
            ),
        ):
            errors = continuation._validate_fp048_r002_r006_contract_correction_seq94(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertTrue(errors)
        correction_authority.preflight_attempt_004_observation.assert_not_called()

    def test_seq95_start_dispatches_r006_and_retired_helpers_stay_closed(
        self,
    ) -> None:
        checkpoint = {"goal_execution": {"transition_history": []}}
        event = {
            "sequence": continuation.FP048_R002_R006_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R006_STARTED_EVENT_ID,
        }
        with mock.patch.object(
            continuation,
            "_fp048_r002_r006_start_gate_contract",
        ) as loader:
            actual = continuation._fp048_r002_start_gate_contract(
                ROOT,
                event=event,
                checkpoint=checkpoint,
            )
        self.assertTrue(
            any("pre-seq97 -004 start is nonauthoritative" in row for row in actual[0])
        )
        loader.assert_not_called()

        retired = continuation._fp048_r002_r005_start_gate_contract(
            ROOT,
            event={
                "sequence": continuation.FP048_R002_R005_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R005_STARTED_EVENT_ID,
            },
            checkpoint=checkpoint,
        )
        self.assertTrue(any("nonauthoritative" in error for error in retired[0]))
        with self.assertRaisesRegex(RuntimeError, "nonauthoritative"):
            continuation._fp048_r002_r005_started_authority()

    def test_r006_start_contract_consumes_only_exact_receipt_and_binding(
        self,
    ) -> None:
        errors, checks, binding, ready = (
            continuation._fp048_r002_r006_start_gate_contract(
                ROOT,
                event={},
                checkpoint={},
            )
        )
        self.assertTrue(any("nonauthoritative" in row for row in errors))
        self.assertEqual((checks, binding, ready), ([], {}, {}))
        with self.assertRaisesRegex(RuntimeError, "nonauthoritative"):
            continuation._fp048_r002_r006_started_authority()


class WalkSafeFp048R002Seq95Seq96R007BoundaryTest(unittest.TestCase):
    @staticmethod
    def _synthetic_correction() -> tuple[
        dict[str, object],
        dict[str, object],
        list[dict[str, object]],
        mock.Mock,
    ]:
        checkpoint, source, history, _prior_authority = (
            WalkSafeFp048R002Seq94Seq95R006BoundaryTest._synthetic_correction()
        )
        passed = copy.deepcopy(
            source["source_checkpoint_binding"]["passed_gate_attempt_003"]
        )
        preflight = {
            "event_id": continuation.FP048_R002_R007_STARTED_EVENT_ID,
            "contract_id": continuation.FP048_R002_R006_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R006_START_GATE_CONTRACT_VERSION
            ),
            "directory": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R007_STARTED_EVENT_ID}"
            ),
            "receipt_path": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R007_STARTED_EVENT_ID}/"
                "implementation-start-gate-receipt.json"
            ),
            "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
            "authority_status": "NONAUTHORITY",
            "event_identity_status": "REUSABLE_UNCONSUMED",
            "namespace_present": False,
            "receipt_present": False,
            "error": "checkpoint gate evidence reference is malformed",
            "reason_code": (
                "R006_CHECKPOINT_NONAUTHORITY_RECEIPT_PATH_NOT_STAGE_AWARE"
            ),
        }
        previous = copy.deepcopy(
            source["contract_supersession"]["replacement_contract_binding"]
        )
        replacement = {
            "schema_version": "1.2",
            "document_id": continuation.FP048_R002_R007_START_GATE_DOCUMENT_ID,
            "path": continuation.FP048_R002_R007_START_GATE_CONTRACT_PATH,
            "file_sha256": "1" * 64,
            "contract_id": continuation.FP048_R002_R007_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R007_START_GATE_CONTRACT_VERSION
            ),
            "canonical_contract_sha256": "2" * 64,
        }
        runner = {
            "path": (
                "scripts/run_walksafe_fp048_r002_goal_start_gate_r007_"
                "20260826.py"
            ),
            "sha256": "3" * 64,
            "byte_length": 1,
        }
        reason = {
            "failed_contract_id": (
                continuation.FP048_R002_R006_START_GATE_CONTRACT_ID
            ),
            "failed_contract_version": (
                continuation.FP048_R002_R006_START_GATE_CONTRACT_VERSION
            ),
            "r006_preflight_attempt_004": copy.deepcopy(preflight),
            "reason_code": preflight["reason_code"],
            "remediation": (
                "SUPERSEDE_R006_WITH_STAGE_AWARE_R007_BEFORE_SEQ96_START"
            ),
        }
        event = copy.deepcopy(source)
        event.update(
            {
                "sequence": (
                    continuation.FP048_R002_R007_CONTRACT_CORRECTION_SEQUENCE
                ),
                "event_id": (
                    continuation.FP048_R002_R007_CONTRACT_CORRECTION_EVENT_ID
                ),
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "previous_event_sha256": source["event_sha256"],
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(source["runtime_after"]),
                "blockers_after": copy.deepcopy(source["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    source["blocker_resolution_ids_after"]
                ),
                "canonical_binding_snapshot_after": copy.deepcopy(
                    source["canonical_binding_snapshot_after"]
                ),
                "source_checkpoint_binding": {
                    **copy.deepcopy(source["source_checkpoint_binding"]),
                    "passed_gate_attempt_003": copy.deepcopy(passed),
                    "r006_preflight_attempt_004": copy.deepcopy(preflight),
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(previous),
                    "reason_code": reason["reason_code"],
                    "replacement_contract_binding": copy.deepcopy(replacement),
                },
                "start_gate_runner_binding": copy.deepcopy(runner),
                "correction_reason": copy.deepcopy(reason),
            }
        )
        event["event_sha256"] = continuation.event_sha256(event)
        history.append(event)
        checkpoint["goal_execution"]["transition_history"] = history
        checkpoint["goal_execution"]["status_by_goal"][
            continuation.FP048_R002_GOAL_ID
        ] = "READY"
        checkpoint["goal_execution"]["goal_status"] = "READY"
        checkpoint["current_work"]["status"] = "READY"
        authority = mock.Mock()
        authority.EVENT_FIELDS = frozenset(event)
        authority.CLAIM_BOUNDARY = copy.deepcopy(event["claim_boundary"])
        authority.CORRECTION_REASON = copy.deepcopy(reason)
        authority.R007_SUCCESSOR_REASON_CODE = reason["reason_code"]
        authority.passed_gate_attempt_003_binding.return_value = passed
        authority.r006_preflight_attempt_004_observation.return_value = preflight
        authority.r006_contract_binding.return_value = previous
        authority.r007_contract_binding.return_value = replacement
        authority.r007_runner_binding.return_value = runner
        authority.require_contract_corrected_checkpoint.return_value = None
        return checkpoint, event, history, authority

    @staticmethod
    def _started_authority(source: dict[str, object]) -> mock.Mock:
        authority = mock.Mock()
        authority.reconstructed_seq95_checkpoint_bytes.return_value = json.dumps(
            source
        ).encode("utf-8")
        authority.require_published_r007_gate_for_seq95.return_value = {
            "document_id": "R007-PASS",
            "path": "implementation-start-gate-receipt.json",
            "file_sha256": "a" * 64,
        }
        authority.require_exact_seq96_projection.return_value = None
        authority.require_started_checkpoint.return_value = None
        return authority

    def test_seq95_dispatches_exact_zero_credit_r007_correction(self) -> None:
        checkpoint, event, history, authority = self._synthetic_correction()
        frozen_authority = mock.Mock()
        frozen_authority.require_frozen_seq95_checkpoint.return_value = None
        frozen_authority.r007_preflight_attempt_004_observation.return_value = {}
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r007_contract_correction_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=frozen_authority,
            ),
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertEqual(errors, [])
        frozen_authority.require_frozen_seq95_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        frozen_authority.r007_preflight_attempt_004_observation.assert_called_once_with(
            ROOT
        )
        authority.r006_preflight_attempt_004_observation.assert_called_once_with(
            ROOT
        )
        authority.require_contract_corrected_checkpoint.assert_not_called()

    def test_seq95_rejects_r007_namespace_before_r008_correction(self) -> None:
        checkpoint, event, history, authority = self._synthetic_correction()
        frozen_authority = mock.Mock()
        frozen_authority.require_frozen_seq95_checkpoint.return_value = None
        frozen_authority.r007_preflight_attempt_004_observation.side_effect = (
            ValueError("R007 namespace must remain absent")
        )
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r007_contract_correction_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=frozen_authority,
            ),
        ):
            errors = continuation._validate_fp048_r002_r007_contract_correction_seq95(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertTrue(
            any("R007 namespace must remain absent" in row for row in errors)
        )

    def test_seq95_rejects_forged_parser_metadata_and_successor(self) -> None:
        for mutation in (
            "receipt_path",
            "parser_error",
            "authority",
            "reusable",
            "namespace",
            "successor",
            "status",
            "float_sequence",
        ):
            with self.subTest(mutation=mutation):
                checkpoint, event, history, authority = self._synthetic_correction()
                preflight = event["source_checkpoint_binding"][
                    "r006_preflight_attempt_004"
                ]
                if mutation == "receipt_path":
                    preflight["receipt_path"] = "forged-receipt.json"
                elif mutation == "parser_error":
                    preflight["error"] = ""
                elif mutation == "authority":
                    preflight["authority_status"] = "AUTHORITY"
                elif mutation == "reusable":
                    preflight["event_identity_status"] = "CONSUMED"
                elif mutation == "namespace":
                    preflight["namespace_present"] = True
                elif mutation == "successor":
                    event["contract_supersession"][
                        "replacement_contract_binding"
                    ] = {}
                elif mutation == "status":
                    event["to_status"] = "IN_PROGRESS"
                    event["status_changes"] = {
                        continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"
                    }
                else:
                    event["sequence"] = 95.0
                event["event_sha256"] = continuation.event_sha256(event)
                with mock.patch.object(
                    continuation,
                    "_fp048_r002_r007_contract_correction_authority",
                    return_value=authority,
                ):
                    errors = continuation._validate_fp048_r002_r007_contract_correction_seq95(
                        ROOT,
                        event=event,
                        checkpoint=checkpoint,
                        history=history,
                    )
                self.assertTrue(errors)

    def test_legacy_direct_seq96_started_descendant_is_rejected(self) -> None:
        checkpoint, _event, history, correction_authority = (
            self._synthetic_correction()
        )
        started = {
            "sequence": continuation.FP048_R002_R007_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R007_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
        }
        history.append(started)
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r007_contract_correction_authority",
                return_value=correction_authority,
            ),
        ):
            with self.assertRaisesRegex(ValueError, "exact R007-to-R008 correction"):
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    history,
                )

    def test_seq95_historical_inverse_uses_new_frozen_public_api(self) -> None:
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826
            as frozen,
        )

        checkpoint = continuation.load_json(CHECKPOINT)
        history = checkpoint["goal_execution"]["transition_history"]
        restored_raw = frozen.reconstructed_seq93_checkpoint_bytes(
            ROOT,
            checkpoint,
        )
        restored = json.loads(restored_raw)
        authority = mock.Mock()
        authority.reconstructed_seq93_checkpoint_bytes.return_value = restored_raw
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r006_contract_correction_authority",
            ) as stale_authority,
        ):
            actual = continuation._fp048_r002_reconstructed_seq93_descendant(
                ROOT,
                checkpoint,
                history,
            )

        self.assertEqual(actual, restored)
        authority.reconstructed_seq93_checkpoint_bytes.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        stale_authority.assert_not_called()

    def test_frozen_inverse_chain_performs_no_current_file_reads(self) -> None:
        from scripts import (
            apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826
            as frozen,
        )

        _source_raw, seq95 = frozen.load_exact_seq95_source(ROOT)
        snapshot = seq95["working_tree_snapshot"]

        def sealed(path: Path, marker: str) -> dict[str, object]:
            return {
                "path": path.as_posix(),
                "sha256": marker * 64,
                "byte_length": 1,
            }

        seq96, _event = frozen.project_seq96(
            ROOT,
            seq95,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T21:20:00+09:00",
            authorization_binding_value=sealed(frozen.AUTHORIZATION_REL, "a"),
            review_binding={
                "assignment": sealed(frozen.REVIEW_ASSIGNMENT_REL, "b"),
                "review_result": sealed(frozen.REVIEW_RESULT_REL, "c"),
                "independent_review": sealed(
                    frozen.INDEPENDENT_REVIEW_REL,
                    "d",
                ),
            },
            r007_preflight_binding=(
                frozen.r007_preflight_attempt_004_observation(ROOT)
            ),
            replacement_contract_binding=frozen.r008_contract_binding(ROOT),
            replacement_runner_binding=frozen.r008_runner_binding(ROOT),
        )
        seq93_raw = frozen.reconstructed_seq93_checkpoint_bytes(ROOT, seq95)
        seq93 = json.loads(seq93_raw)
        with (
            mock.patch.object(
                frozen.seq90,
                "_stable_read",
                side_effect=AssertionError("CURRENT_FILE_READ"),
            ),
            mock.patch.object(
                Path,
                "read_bytes",
                side_effect=AssertionError("CURRENT_FILE_READ"),
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r007_contract_correction_authority",
                side_effect=AssertionError("CURRENT_FILE_READ"),
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_started_authority",
                side_effect=AssertionError("CURRENT_FILE_READ"),
            ),
        ):
            for descendant in (seq95, seq96):
                history = descendant["goal_execution"]["transition_history"]
                recovered = (
                    continuation._fp048_r002_reconstructed_seq93_descendant(
                        ROOT,
                        descendant,
                        history,
                    )
                )
                self.assertEqual(recovered, seq93)
                seq92_raw = (
                    continuation._fp048_r002_reconstructed_seq92_from_frozen_seq93(
                        ROOT,
                        recovered,
                    )
                )
                self.assertEqual(
                    hashlib.sha256(seq92_raw).hexdigest(),
                    continuation.FP048_R002_FROZEN_SEQ92_CHECKPOINT_SHA256,
                )
                self.assertEqual(
                    len(seq92_raw),
                    continuation.FP048_R002_FROZEN_SEQ92_CHECKPOINT_BYTE_LENGTH,
                )


    def test_new_authority_loaders_fail_closed_on_missing_public_api(self) -> None:
        import scripts as scripts_package

        with mock.patch.object(
            scripts_package,
            "apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826",
            object(),
            create=True,
        ):
            with self.assertRaisesRegex(RuntimeError, "API is missing"):
                continuation._fp048_r002_r007_contract_correction_authority()
        with mock.patch.object(
            scripts_package,
            "apply_walksafe_fp048_r002_goal_started_seq96_20260826",
            object(),
            create=True,
        ):
            with self.assertRaisesRegex(RuntimeError, "API is missing"):
                continuation._fp048_r002_r007_started_authority()

    def test_seq96_r007_direct_start_is_rejected(self) -> None:
        checkpoint = {"goal_execution": {"transition_history": []}}
        seq96 = {
            "sequence": continuation.FP048_R002_R007_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R007_STARTED_EVENT_ID,
        }
        with mock.patch.object(
            continuation,
            "_fp048_r002_r007_start_gate_contract",
        ) as loader:
            actual = continuation._fp048_r002_start_gate_contract(
                ROOT, event=seq96, checkpoint=checkpoint
            )
        self.assertTrue(any("nonauthoritative" in row for row in actual[0]))
        loader.assert_not_called()

        for sequence in (95, 96.0, 94):
            with self.subTest(sequence=sequence):
                rejected = continuation._fp048_r002_start_gate_contract(
                    ROOT,
                    event={
                        "sequence": sequence,
                        "event_id": continuation.FP048_R002_R007_STARTED_EVENT_ID,
                    },
                    checkpoint=checkpoint,
                )
                self.assertTrue(
                    any("nonauthoritative" in row for row in rejected[0])
                )

    def test_retired_r007_start_helper_rejects_direct_consumption(self) -> None:
        errors, checks, binding, ready = (
            continuation._fp048_r002_r007_start_gate_contract(
                ROOT,
                event={},
                checkpoint={},
            )
        )
        self.assertTrue(any("nonauthoritative" in row for row in errors))
        self.assertEqual((checks, binding, ready), ([], {}, {}))



class WalkSafeFp048R002Seq96Seq97R008BoundaryTest(unittest.TestCase):
    @staticmethod
    def _synthetic_correction() -> tuple[
        dict[str, object],
        dict[str, object],
        list[dict[str, object]],
        mock.Mock,
    ]:
        checkpoint, source, history, _prior_authority = (
            WalkSafeFp048R002Seq95Seq96R007BoundaryTest._synthetic_correction()
        )
        preflight = {
            "event_id": continuation.FP048_R002_R008_STARTED_EVENT_ID,
            "contract_id": continuation.FP048_R002_R007_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R007_START_GATE_CONTRACT_VERSION
            ),
            "directory": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R008_STARTED_EVENT_ID}"
            ),
            "receipt_path": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R008_STARTED_EVENT_ID}/"
                "implementation-start-gate-receipt.json"
            ),
            "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
            "authority_status": "NONAUTHORITY",
            "event_identity_status": "REUSABLE_UNCONSUMED",
            "namespace_present": False,
            "receipt_present": False,
            "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
            "exit_code": 1,
            "error": (
                "ROOT_FP048_R002_CONTROL_REGRESSION failed with exit code 1; "
                "see PREVIEW_ONLY"
            ),
            "reason_code": (
                "R007_ROOT_REGRESSION_INCLUDED_PREPUBLICATION_ONLY_SEQ95_TESTS"
            ),
        }
        previous = copy.deepcopy(
            source["contract_supersession"]["replacement_contract_binding"]
        )
        replacement = {
            "schema_version": "1.2",
            "document_id": continuation.FP048_R002_R008_START_GATE_DOCUMENT_ID,
            "path": continuation.FP048_R002_R008_START_GATE_CONTRACT_PATH,
            "file_sha256": "5" * 64,
            "contract_id": continuation.FP048_R002_R008_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R008_START_GATE_CONTRACT_VERSION
            ),
            "canonical_contract_sha256": "6" * 64,
        }
        runner = {
            "path": (
                "scripts/run_walksafe_fp048_r002_goal_start_gate_r008_"
                "20260826.py"
            ),
            "sha256": "7" * 64,
            "byte_length": 1,
        }
        reason_code = preflight["reason_code"]
        reason = {
            "failed_contract_id": (
                continuation.FP048_R002_R007_START_GATE_CONTRACT_ID
            ),
            "failed_contract_version": (
                continuation.FP048_R002_R007_START_GATE_CONTRACT_VERSION
            ),
            "r007_preflight_attempt_004": copy.deepcopy(preflight),
            "reason_code": reason_code,
            "remediation": (
                "SUPERSEDE_R007_WITH_STAGE_AWARE_R008_BEFORE_SEQ97_START"
            ),
        }
        event = copy.deepcopy(source)
        event.update(
            {
                "sequence": (
                    continuation.FP048_R002_R008_CONTRACT_CORRECTION_SEQUENCE
                ),
                "event_id": (
                    continuation.FP048_R002_R008_CONTRACT_CORRECTION_EVENT_ID
                ),
                "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
                "previous_event_sha256": source["event_sha256"],
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
                "runtime_after": copy.deepcopy(source["runtime_after"]),
                "blockers_after": copy.deepcopy(source["blockers_after"]),
                "blocker_resolution_ids_after": copy.deepcopy(
                    source["blocker_resolution_ids_after"]
                ),
                "canonical_binding_snapshot_after": copy.deepcopy(
                    source["canonical_binding_snapshot_after"]
                ),
                "source_checkpoint_binding": {
                    **copy.deepcopy(source["source_checkpoint_binding"]),
                    "r007_preflight_attempt_004": copy.deepcopy(preflight),
                },
                "contract_supersession": {
                    "previous_contract_binding": copy.deepcopy(previous),
                    "reason_code": reason_code,
                    "replacement_contract_binding": copy.deepcopy(replacement),
                },
                "start_gate_runner_binding": copy.deepcopy(runner),
                "correction_reason": copy.deepcopy(reason),
            }
        )
        event["event_sha256"] = continuation.event_sha256(event)
        history.append(event)
        checkpoint["goal_execution"]["transition_history"] = history
        checkpoint["goal_execution"]["status_by_goal"][
            continuation.FP048_R002_GOAL_ID
        ] = "READY"
        checkpoint["goal_execution"]["goal_status"] = "READY"
        checkpoint["current_work"]["status"] = "READY"
        authority = mock.Mock()
        authority.EVENT_FIELDS = frozenset(event)
        authority.CLAIM_BOUNDARY = copy.deepcopy(event["claim_boundary"])
        authority.CORRECTION_REASON = copy.deepcopy(reason)
        authority.R008_SUCCESSOR_REASON_CODE = reason_code
        authority.r007_preflight_attempt_004_observation.return_value = preflight
        authority.r007_contract_binding.return_value = previous
        authority.r008_contract_binding.return_value = replacement
        authority.r008_runner_binding.return_value = runner
        authority.require_contract_corrected_checkpoint.return_value = None
        return checkpoint, event, history, authority

    @staticmethod
    def _started_authority(source: dict[str, object]) -> mock.Mock:
        authority = mock.Mock()
        authority.reconstructed_seq96_checkpoint_bytes.return_value = json.dumps(
            source
        ).encode("utf-8")
        authority.require_published_r008_gate_for_seq96.return_value = {
            "document_id": "R008-PASS",
            "path": "implementation-start-gate-receipt.json",
            "file_sha256": "8" * 64,
        }
        authority.require_exact_seq97_projection.return_value = None
        authority.require_started_checkpoint.return_value = None
        return authority

    def test_seq96_dispatches_exact_zero_credit_r008_correction(self) -> None:
        checkpoint, event, history, authority = self._synthetic_correction()
        with mock.patch.object(
            continuation,
            "_fp048_r002_r008_contract_correction_authority",
            return_value=authority,
        ):
            errors = continuation._validate_npc_single_admin_recovery_control_reanchor(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertEqual(errors, [])
        authority.require_contract_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=True,
            run_external_validators=False,
        )
        authority.r007_preflight_attempt_004_observation.assert_called_once_with(
            ROOT
        )

    def test_seq96_postgate_requires_exact_published_r008_pass(self) -> None:
        checkpoint, event, history, authority = self._synthetic_correction()
        started_authority = self._started_authority(checkpoint)
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_gate_namespace_present",
                return_value=True,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_started_authority",
                return_value=started_authority,
            ),
        ):
            errors = continuation._validate_fp048_r002_r008_contract_correction_seq96(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )

        self.assertEqual(errors, [])
        started_authority.require_published_r008_gate_for_seq96.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        authority.require_contract_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        authority.r007_preflight_attempt_004_observation.assert_not_called()

        started_authority.require_published_r008_gate_for_seq96.side_effect = (
            ValueError("R008 receipt differs")
        )
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_gate_namespace_present",
                return_value=True,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_started_authority",
                return_value=started_authority,
            ),
        ):
            rejected = continuation._validate_fp048_r002_r008_contract_correction_seq96(
                ROOT,
                event=event,
                checkpoint=checkpoint,
                history=history,
            )
        self.assertTrue(any("R008 receipt differs" in row for row in rejected))

    def test_seq96_rejects_forged_preflight_successor_and_credit(self) -> None:
        for mutation in (
            "failed_check",
            "float_exit",
            "error",
            "reason",
            "namespace",
            "successor",
            "status",
            "float_sequence",
        ):
            with self.subTest(mutation=mutation):
                checkpoint, event, history, authority = self._synthetic_correction()
                preflight = event["source_checkpoint_binding"][
                    "r007_preflight_attempt_004"
                ]
                if mutation == "failed_check":
                    preflight["failed_check_id"] = "FORGED"
                elif mutation == "float_exit":
                    preflight["exit_code"] = 1.0
                elif mutation == "error":
                    preflight["error"] = ""
                elif mutation == "reason":
                    preflight["reason_code"] = "FORGED"
                elif mutation == "namespace":
                    preflight["namespace_present"] = True
                elif mutation == "successor":
                    event["contract_supersession"][
                        "replacement_contract_binding"
                    ] = {}
                elif mutation == "status":
                    event["to_status"] = "IN_PROGRESS"
                    event["status_changes"] = {
                        continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"
                    }
                else:
                    event["sequence"] = 96.0
                event["event_sha256"] = continuation.event_sha256(event)
                with mock.patch.object(
                    continuation,
                    "_fp048_r002_r008_contract_correction_authority",
                    return_value=authority,
                ):
                    errors = continuation._validate_fp048_r002_r008_contract_correction_seq96(
                        ROOT,
                        event=event,
                        checkpoint=checkpoint,
                        history=history,
                    )
                self.assertTrue(errors)

    def test_legacy_direct_seq97_descendant_is_rejected(self) -> None:
        checkpoint, _event, history, correction_authority = (
            self._synthetic_correction()
        )
        seq96_source = copy.deepcopy(checkpoint)
        started = {
            "sequence": continuation.FP048_R002_R008_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R008_STARTED_EVENT_ID,
            "event_type": "GOAL_STARTED",
        }
        history.append(started)
        with self.assertRaisesRegex(ValueError, "legacy direct seq97"):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                history,
            )

    def test_seq97_dispatches_only_r008_and_rejects_legacy_seq96(self) -> None:
        checkpoint = {"goal_execution": {"transition_history": []}}
        seq97 = {
            "sequence": continuation.FP048_R002_R008_STARTED_SEQUENCE,
            "event_id": continuation.FP048_R002_R008_STARTED_EVENT_ID,
        }
        expected = ([], [], {"contract_id": "R008"}, {})
        with mock.patch.object(
            continuation,
            "_fp048_r002_r008_start_gate_contract",
            return_value=expected,
        ) as loader:
            actual = continuation._fp048_r002_start_gate_contract(
                ROOT,
                event=seq97,
                checkpoint=checkpoint,
            )
        self.assertTrue(any("legacy direct seq97" in row for row in actual[0]))
        loader.assert_not_called()

        for sequence in (96, 97.0, 95):
            with self.subTest(sequence=sequence):
                rejected = continuation._fp048_r002_start_gate_contract(
                    ROOT,
                    event={
                        "sequence": sequence,
                        "event_id": continuation.FP048_R002_R008_STARTED_EVENT_ID,
                    },
                    checkpoint=checkpoint,
                )
                self.assertTrue(
                    any("nonauthoritative" in row for row in rejected[0])
                )

    def test_seq96_seq97_replay_uses_sealed_r006_preflight(self) -> None:
        for terminal_sequence in (
            continuation.FP048_R002_R008_CONTRACT_CORRECTION_SEQUENCE,
            continuation.FP048_R002_R008_STARTED_SEQUENCE,
        ):
            with self.subTest(terminal_sequence=terminal_sequence):
                checkpoint, _correction, history, correction_authority = (
                    self._synthetic_correction()
                )
                seq95_event = history[94]
                _old_checkpoint, _old_event, _old_history, seq95_authority = (
                    WalkSafeFp048R002Seq95Seq96R007BoundaryTest._synthetic_correction()
                )
                seq95_authority.r006_preflight_attempt_004_observation.side_effect = (
                    AssertionError("PHYSICAL_R006_PREFLIGHT_RECHECK")
                )
                started_authority = None
                if terminal_sequence == continuation.FP048_R002_R008_STARTED_SEQUENCE:
                    seq96_source = copy.deepcopy(checkpoint)
                    history.append(
                        {
                            "sequence": continuation.FP048_R002_R008_STARTED_SEQUENCE,
                            "event_id": continuation.FP048_R002_R008_STARTED_EVENT_ID,
                            "event_type": "GOAL_STARTED",
                        }
                    )
                    started_authority = self._started_authority(seq96_source)
                patches = [
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_r007_contract_correction_authority",
                        return_value=seq95_authority,
                    ),
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_r008_contract_correction_authority",
                        return_value=correction_authority,
                    ),
                ]
                if started_authority is not None:
                    patches.append(
                        mock.patch.object(
                            continuation,
                            "_fp048_r002_r008_started_authority",
                            return_value=started_authority,
                        )
                    )
                with patches[0], patches[1]:
                    if started_authority is None:
                        errors = continuation._validate_fp048_r002_r007_contract_correction_seq95(
                            ROOT,
                            event=seq95_event,
                            checkpoint=checkpoint,
                            history=history,
                        )
                    else:
                        with patches[2]:
                            errors = continuation._validate_fp048_r002_r007_contract_correction_seq95(
                                ROOT,
                                event=seq95_event,
                                checkpoint=checkpoint,
                                history=history,
                            )
                if terminal_sequence == continuation.FP048_R002_R008_STARTED_SEQUENCE:
                    self.assertTrue(
                        any("legacy direct seq97" in row for row in errors)
                    )
                else:
                    self.assertEqual(errors, [])
                seq95_authority.r006_preflight_attempt_004_observation.assert_not_called()

    def test_r008_start_consumes_exact_receipt_and_contract(self) -> None:
        checkpoint, correction, history, correction_authority = (
            self._synthetic_correction()
        )
        seq96_source = copy.deepcopy(checkpoint)
        live = continuation.load_json(CHECKPOINT)
        history[88] = copy.deepcopy(
            live["goal_execution"]["transition_history"][88]
        )
        receipt = {
            "document_id": "WS-FP048-R002-R008-START-004",
            "path": (
                "docs/control/execution/goal-gates/"
                f"{continuation.FP048_R002_R008_STARTED_EVENT_ID}/"
                "implementation-start-gate-receipt.json"
            ),
            "file_sha256": "8" * 64,
        }
        started = WalkSafeFp048R002Seq90Seq91BoundaryTest._seq91_event(correction)
        started.update(
            {
                "sequence": continuation.FP048_R002_R008_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R008_STARTED_EVENT_ID,
                "previous_event_sha256": correction["event_sha256"],
                "implementation_start_gate_binding": copy.deepcopy(receipt),
            }
        )
        started["event_sha256"] = continuation.event_sha256(started)
        history.append(started)
        checkpoint["goal_execution"]["status_by_goal"][
            continuation.FP048_R002_GOAL_ID
        ] = "IN_PROGRESS"
        checkpoint["goal_execution"]["goal_status"] = "IN_PROGRESS"
        checkpoint["current_work"]["status"] = "IN_PROGRESS"
        replacement = correction_authority.r008_contract_binding.return_value
        contract = {
            "schema_version": "1.2",
            "document_id": continuation.FP048_R002_R008_START_GATE_DOCUMENT_ID,
            "contract_id": continuation.FP048_R002_R008_START_GATE_CONTRACT_ID,
            "contract_version": (
                continuation.FP048_R002_R008_START_GATE_CONTRACT_VERSION
            ),
            "target_goal_id": continuation.FP048_R002_GOAL_ID,
            "target_goal_content_sha256": continuation.FP048_R002_GOAL_SHA256,
            "gate_purpose": "INITIAL_START",
            "ordered_checks": [
                {"check_id": check_id, "command": f"run-{check_id}"}
                for check_id in continuation.FP048_R002_START_GATE_CHECK_IDS
            ],
        }
        correction_authority.load_r008_contract.return_value = (
            contract,
            replacement,
        )
        started_authority = self._started_authority(seq96_source)
        started_authority.goal_start_gate_receipt_binding.return_value = receipt
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=correction_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_started_authority",
                return_value=started_authority,
            ),
        ):
            errors, checks, binding, ready = (
                continuation._fp048_r002_r008_start_gate_contract(
                    ROOT,
                    event=started,
                    checkpoint=checkpoint,
                )
            )

        self.assertEqual(errors, [])
        self.assertEqual(
            [row["check_id"] for row in checks],
            continuation.FP048_R002_START_GATE_CHECK_IDS,
        )
        self.assertEqual(binding, replacement)
        self.assertEqual(ready["event_id"], continuation.FP048_R002_READY_EVENT_ID)

        started["implementation_start_gate_binding"]["file_sha256"] = "0" * 64
        started["event_sha256"] = continuation.event_sha256(started)
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=correction_authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_started_authority",
                return_value=started_authority,
            ),
        ):
            tampered, _checks, _binding, _ready = (
                continuation._fp048_r002_r008_start_gate_contract(
                    ROOT,
                    event=started,
                    checkpoint=checkpoint,
                )
            )
        self.assertTrue(any("reviewed receipt binding" in row for row in tampered))

    def test_new_r008_authority_loaders_fail_closed_without_public_api(self) -> None:
        import scripts as scripts_package

        with mock.patch.object(
            scripts_package,
            "apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826",
            object(),
            create=True,
        ):
            with self.assertRaisesRegex(RuntimeError, "API is missing"):
                continuation._fp048_r002_r008_contract_correction_authority()
        with mock.patch.object(
            scripts_package,
            "apply_walksafe_fp048_r002_goal_started_seq97_20260826",
            object(),
            create=True,
        ):
            with self.assertRaisesRegex(RuntimeError, "API is missing"):
                continuation._fp048_r002_r008_started_authority()


class WalkSafeFp048R002Seq97Seq98R009BoundaryTest(unittest.TestCase):
    @staticmethod
    def _seq97_fixture() -> tuple[dict, dict, mock.Mock, mock.Mock, mock.Mock]:
        checkpoint, _event, history, r008 = (
            WalkSafeFp048R002Seq96Seq97R008BoundaryTest._synthetic_correction()
        )
        seq96 = copy.deepcopy(checkpoint)
        correction = {
            "sequence": continuation.FP048_R002_R009_CONTRACT_CORRECTION_SEQUENCE,
            "event_id": continuation.FP048_R002_R009_CONTRACT_CORRECTION_EVENT_ID,
            "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
        }
        history.append(correction)
        checkpoint["goal_execution"]["transition_history"] = history
        seq96_raw = continuation.canonical_json_bytes(seq96)
        seq97_raw = continuation.canonical_json_bytes(checkpoint)
        r008.canonical_seq96_checkpoint_bytes.return_value = seq96_raw
        r009 = mock.Mock()
        r009.reconstructed_seq96_checkpoint_bytes.return_value = seq96_raw
        r009.canonical_seq97_checkpoint_bytes.return_value = seq97_raw
        r009.require_snapshot_hygiene_corrected_checkpoint.return_value = None
        execution = mock.Mock()
        execution.CORRECTION_EVENT_TYPE = "GOAL_START_GATE_EXECUTION_CORRECTED"
        execution.r009_execution_failure_binding.return_value = {
            "event_identity_status": "CONSUMED_FAILED_NO_RECEIPT"
        }
        return checkpoint, seq96, r008, r009, execution

    def test_exact_seq97_reconstructs_seq96_through_public_authority(self) -> None:
        checkpoint, _seq96, r008, r009, execution = self._seq97_fixture()
        history = checkpoint["goal_execution"]["transition_history"]
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=r008,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_contract_correction_authority",
                return_value=r009,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_execution_correction_authority",
                return_value=execution,
            ),
        ):
            self.assertTrue(
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    history,
                )
            )
        r009.require_snapshot_hygiene_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )

    def test_exact_seq98_reconstructs_seq97_and_inverse_tamper_fails(self) -> None:
        checkpoint, _seq96, r008, r009, execution = self._seq97_fixture()
        seq97 = copy.deepcopy(checkpoint)
        seq97_raw = continuation.canonical_json_bytes(seq97)
        checkpoint["goal_execution"]["transition_history"].append(
            {
                "sequence": continuation.FP048_R002_R009_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R009_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        starter = mock.Mock()
        starter.reconstructed_seq97_checkpoint_bytes.return_value = seq97_raw
        starter.require_started_checkpoint.return_value = None
        history = checkpoint["goal_execution"]["transition_history"]
        patches = (
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=r008,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_contract_correction_authority",
                return_value=r009,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_execution_correction_authority",
                return_value=execution,
            ),
        )
        with patches[0], patches[1], patches[2], patches[3]:
            with self.assertRaisesRegex(ValueError, "legacy direct seq98 R009"):
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    history,
                )

    def test_retired_seq98_start_plus_seq99_update_is_rejected_by_successor_dispatch(
        self,
    ) -> None:
        checkpoint, *_rest = self._seq97_fixture()
        history = checkpoint["goal_execution"]["transition_history"]
        history.extend(
            [
                {
                    "sequence": continuation.FP048_R002_R009_STARTED_SEQUENCE,
                    "event_id": continuation.FP048_R002_R009_STARTED_EVENT_ID,
                    "event_type": "GOAL_STARTED",
                },
                {
                    "sequence": continuation.FP048_R002_COMPLETION_EVIDENCE_SEQUENCE,
                    "event_id": continuation.FP048_R002_COMPLETION_EVIDENCE_EVENT_ID,
                    "event_type": "CANONICAL_BINDINGS_UPDATED",
                },
            ]
        )
        with self.assertRaisesRegex(
            ValueError,
            "seq99 successor identity or status differs",
        ):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                history,
            )


class WalkSafeFp048R002Seq98Seq99R010BoundaryTest(unittest.TestCase):
    @staticmethod
    def _generic_seq98_history() -> list[dict]:
        checkpoint = continuation.load_json(CHECKPOINT)
        history = copy.deepcopy(
            checkpoint["goal_execution"]["transition_history"][:97]
        )
        if len(history) != 97:
            raise AssertionError("exact seq97 generic replay source is missing")
        tail = history[-1]
        event = {
            "sequence": 98,
            "event_id": (
                continuation.FP048_R002_R009_EXECUTION_CORRECTION_EVENT_ID
            ),
            "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
            "previous_event_sha256": tail["event_sha256"],
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
            "subject_goal_id": continuation.FP048_R002_GOAL_ID,
            "focus_goal_id": continuation.FP048_R002_GOAL_ID,
            "static_plan_manifest_sha256": tail[
                "static_plan_manifest_sha256"
            ],
        }
        event["event_sha256"] = continuation.event_sha256(event)
        history.append(event)
        return history

    @staticmethod
    def _reseal(history: list[dict]) -> None:
        previous = ""
        for event in history:
            event["previous_event_sha256"] = previous
            event["event_sha256"] = continuation.event_sha256(event)
            previous = event["event_sha256"]

    @staticmethod
    def _fixture() -> tuple[dict, bytes, mock.Mock, mock.Mock, mock.Mock]:
        checkpoint, _seq96, r008, r009, execution = (
            WalkSafeFp048R002Seq97Seq98R009BoundaryTest._seq97_fixture()
        )
        seq97 = copy.deepcopy(checkpoint)
        seq97_raw = continuation.canonical_json_bytes(seq97)
        correction = {
            "sequence": continuation.FP048_R002_R009_EXECUTION_CORRECTION_SEQUENCE,
            "event_id": continuation.FP048_R002_R009_EXECUTION_CORRECTION_EVENT_ID,
            "event_type": execution.CORRECTION_EVENT_TYPE,
        }
        checkpoint["goal_execution"]["transition_history"].append(correction)
        execution.reconstructed_seq97_checkpoint_bytes.return_value = seq97_raw
        execution.canonical_seq98_checkpoint_bytes.return_value = (
            continuation.canonical_json_bytes(checkpoint)
        )
        execution.require_start_gate_execution_corrected_checkpoint.return_value = None
        return checkpoint, seq97_raw, r008, r009, execution

    @staticmethod
    def _patches(
        r008: mock.Mock,
        r009: mock.Mock,
        execution: mock.Mock,
        starter: mock.Mock | None = None,
    ) -> tuple[object, ...]:
        patches: list[object] = [
            mock.patch.object(
                continuation,
                "_fp048_r002_r008_contract_correction_authority",
                return_value=r008,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_contract_correction_authority",
                return_value=r009,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r009_execution_correction_authority",
                return_value=execution,
            ),
        ]
        if starter is not None:
            patches.append(
                mock.patch.object(
                    continuation,
                    "_fp048_r002_r010_started_authority",
                    return_value=starter,
                )
            )
        return tuple(patches)

    def test_exact_seq98_uses_public_inverse_and_failed_seq99_is_rejected(self) -> None:
        checkpoint, _seq97_raw, r008, r009, execution = self._fixture()
        patches = self._patches(r008, r009, execution)
        with patches[0], patches[1], patches[2]:
            self.assertTrue(
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    checkpoint["goal_execution"]["transition_history"],
                )
            )
        execution.require_start_gate_execution_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=True,
            run_external_validators=False,
        )

        checkpoint["goal_execution"]["transition_history"].append(
            {
                "sequence": continuation.FP048_R002_R010_STARTED_SEQUENCE,
                "event_id": continuation.FP048_R002_R010_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        with self.assertRaisesRegex(ValueError, "legacy failed R010"):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                checkpoint["goal_execution"]["transition_history"],
            )

    def test_projected_seq99_marks_inverse_seq98_as_historical(self) -> None:
        checkpoint, _seq97_raw, r008, r009, execution = self._fixture()
        checkpoint["goal_execution"]["transition_history"][-1].update(
            {
                "subject_goal_id": continuation.FP048_R002_GOAL_ID,
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
            }
        )
        seq98_raw = (
            json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")
        projected = copy.deepcopy(checkpoint)
        projected["goal_execution"]["transition_history"].append(
            {
                "sequence": continuation.FP048_R002_SUCCESSOR_CORRECTION_SEQUENCE,
                "event_id": continuation.FP048_R002_SUCCESSOR_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
                "subject_goal_id": continuation.FP048_R002_GOAL_ID,
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
            }
        )
        successor = mock.Mock()
        successor.reconstructed_seq98_checkpoint_bytes.return_value = seq98_raw
        patches = self._patches(r008, r009, execution)
        with (
            patches[0],
            patches[1],
            patches[2],
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_correction_authority",
                return_value=successor,
            ),
        ):
            self.assertTrue(
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    projected,
                    projected["goal_execution"]["transition_history"],
                )
            )
        execution.require_start_gate_execution_corrected_checkpoint.assert_called_once_with(
            ROOT,
            checkpoint,
            require_live_snapshot=False,
            run_external_validators=False,
        )

    def test_direct_seq98_live_drift_is_not_bypassed(self) -> None:
        checkpoint, _seq97_raw, r008, r009, execution = self._fixture()
        execution.require_start_gate_execution_corrected_checkpoint.side_effect = (
            RuntimeError("direct seq98 live drift")
        )
        patches = self._patches(r008, r009, execution)
        with patches[0], patches[1], patches[2], self.assertRaisesRegex(
            RuntimeError,
            "direct seq98 live drift",
        ):
            continuation._require_fp048_r002_branch_semantics_descendant(
                ROOT,
                checkpoint,
                checkpoint["goal_execution"]["transition_history"],
            )
        self.assertTrue(
            execution.require_start_gate_execution_corrected_checkpoint.call_args.kwargs[
                "require_live_snapshot"
            ]
        )

    def test_failure_binding_and_inverse_tamper_fail_closed(self) -> None:
        checkpoint, seq97_raw, r008, r009, execution = self._fixture()
        execution.r009_execution_failure_binding.return_value = {}
        patches = self._patches(r008, r009, execution)
        with patches[0], patches[1], patches[2]:
            with self.assertRaisesRegex(ValueError, "failed execution binding"):
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    checkpoint["goal_execution"]["transition_history"],
                )
        execution.r009_execution_failure_binding.return_value = {"status": "FAILED"}
        execution.reconstructed_seq97_checkpoint_bytes.return_value = seq97_raw + b" "
        with patches[0], patches[1], patches[2]:
            with self.assertRaisesRegex(ValueError, "noncanonical"):
                continuation._require_fp048_r002_branch_semantics_descendant(
                    ROOT,
                    checkpoint,
                    checkpoint["goal_execution"]["transition_history"],
                )

    def test_seq98_execution_correction_has_exact_generic_ready_replay(
        self,
    ) -> None:
        history = self._generic_seq98_history()

        self.assertEqual(continuation.validate_generic_event_order(history), [])
        self.assertEqual(
            continuation._expected_status_change(
                "GOAL_START_GATE_EXECUTION_CORRECTED",
                history[-1],
                {continuation.FP048_R002_GOAL_ID: "READY"},
            ),
            ({}, None),
        )
        self.assertEqual(
            continuation._expected_from_to(
                "GOAL_START_GATE_EXECUTION_CORRECTED",
                history[-1],
                {continuation.FP048_R002_GOAL_ID: "READY"},
                {},
            ),
            ("READY", "READY"),
        )

    def test_seq98_execution_correction_generic_tamper_fails_closed(
        self,
    ) -> None:
        for mutation in ("status_changes", "from_to", "unknown_type", "sole_ready"):
            with self.subTest(mutation=mutation):
                history = self._generic_seq98_history()
                event = history[-1]
                if mutation == "status_changes":
                    event["status_changes"] = {
                        continuation.FP048_R002_GOAL_ID: "READY"
                    }
                elif mutation == "from_to":
                    event["from_status"] = "IN_PROGRESS"
                elif mutation == "unknown_type":
                    event["event_type"] = "UNKNOWN_EXECUTION_CORRECTION"
                else:
                    history[0]["status_changes"]["WS-OTHER-ACTIVE"] = (
                        "IN_PROGRESS"
                    )
                self._reseal(history)

                errors = continuation.validate_generic_event_order(history)

                self.assertTrue(errors)
                if mutation == "status_changes":
                    self.assertTrue(
                        any("status transition differs" in row for row in errors)
                    )
                elif mutation == "from_to":
                    self.assertTrue(any("from/to" in row for row in errors))
                elif mutation == "unknown_type":
                    self.assertTrue(any("type is invalid" in row for row in errors))
                else:
                    self.assertTrue(any("sole READY" in row for row in errors))

    def test_seq98_execution_correction_uses_exact_authority_dispatch(
        self,
    ) -> None:
        history = self._generic_seq98_history()
        event = history[-1]
        checkpoint = {"goal_execution": {"transition_history": history}}
        expected = ["exact seq98 authority called"]
        with mock.patch.object(
            continuation,
            "_validate_fp048_r002_r009_execution_correction_seq98",
            return_value=expected,
        ) as authority:
            self.assertEqual(
                continuation._validate_npc_single_admin_recovery_control_reanchor(
                    ROOT,
                    event=event,
                    checkpoint=checkpoint,
                    history=history,
                ),
                expected,
            )
        authority.assert_called_once_with(
            ROOT,
            event=event,
            checkpoint=checkpoint,
            history=history,
        )


class WalkSafeFp048R002ContinuationCallCacheTest(unittest.TestCase):
    @staticmethod
    def _checkpoint() -> dict[str, object]:
        return {
            "goal_execution": {
                "transition_history": [
                    {
                        "sequence": 98,
                        "event_id": "SYNTHETIC-98",
                        "event_sha256": "1" * 64,
                    }
                ],
                "transition_history_anchor_sha256": "2" * 64,
                "goal_status": {continuation.FP048_R002_GOAL_ID: "READY"},
            },
            "working_tree_snapshot": {
                "path_set_sha256": "3" * 64,
                "content_set_sha256": "4" * 64,
            },
            "current_work": {"release_completion_claimed": False},
        }

    def _cached_source(
        self,
        checkpoint: dict[str, object],
        *,
        root: Path = ROOT,
        historical: bool = False,
    ) -> dict[str, object] | None:
        history = checkpoint["goal_execution"]["transition_history"]
        return continuation._fp048_r002_current_descendant_seq96_source(
            root,
            checkpoint,
            history,
            _historical_successor_source=historical,
        )

    def test_one_validate_call_caches_source_and_returns_defensive_copies(
        self,
    ) -> None:
        checkpoint = self._checkpoint()
        checkpoint_before = copy.deepcopy(checkpoint)
        source = {"payload": {"items": []}}
        compute = mock.Mock(return_value=source)

        def fake_validate(*args: object, **kwargs: object) -> list[str]:
            del args, kwargs
            first = self._cached_source(checkpoint)
            first["payload"]["items"].append("caller mutation")
            observed = [self._cached_source(checkpoint) for _ in range(9)]
            self.assertTrue(all(row == {"payload": {"items": []}} for row in observed))
            self.assertEqual(len({id(row) for row in observed}), 9)
            return []

        with (
            mock.patch.object(
                continuation,
                "_compute_fp048_r002_current_descendant_seq96_source",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate",
                side_effect=fake_validate,
            ),
        ):
            self.assertEqual(continuation.validate(ROOT), [])
        compute.assert_called_once()
        self.assertEqual(checkpoint, checkpoint_before)
        self.assertIsNone(
            continuation._FP048_R002_CONTINUATION_CALL_CACHE.get()
        )
        with mock.patch.object(
            continuation,
            "_validate_transition_replay",
            side_effect=RuntimeError("replay failed"),
        ):
            with self.assertRaisesRegex(RuntimeError, "replay failed"):
                continuation.validate_transition_replay(ROOT, checkpoint, {})
        self.assertIsNone(
            continuation._FP048_R002_CONTINUATION_CALL_CACHE.get()
        )

    def test_none_error_and_interrupt_cache_semantics(self) -> None:
        checkpoint = self._checkpoint()

        for label, outcome in (
            ("none", None),
            ("error", ValueError("sealed failure")),
        ):
            with self.subTest(label=label):
                compute = mock.Mock(
                    side_effect=outcome if isinstance(outcome, Exception) else None,
                    return_value=outcome,
                )

                def fake_validate(*args: object, **kwargs: object) -> list[str]:
                    del args, kwargs
                    for _ in range(2):
                        if isinstance(outcome, Exception):
                            with self.assertRaisesRegex(ValueError, "sealed failure"):
                                self._cached_source(checkpoint)
                        else:
                            self.assertIsNone(self._cached_source(checkpoint))
                    return []

                with (
                    mock.patch.object(
                        continuation,
                        "_compute_fp048_r002_current_descendant_seq96_source",
                        compute,
                    ),
                    mock.patch.object(
                        continuation,
                        "_validate",
                        side_effect=fake_validate,
                    ),
                ):
                    self.assertEqual(continuation.validate(ROOT), [])
                compute.assert_called_once()

        compute = mock.Mock(
            side_effect=[KeyboardInterrupt("stop"), {"recomputed": True}]
        )

        def fake_interrupt(*args: object, **kwargs: object) -> list[str]:
            del args, kwargs
            with self.assertRaises(KeyboardInterrupt):
                self._cached_source(checkpoint)
            self.assertEqual(
                self._cached_source(checkpoint),
                {"recomputed": True},
            )
            return []

        with (
            mock.patch.object(
                continuation,
                "_compute_fp048_r002_current_descendant_seq96_source",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate",
                side_effect=fake_interrupt,
            ),
        ):
            self.assertEqual(continuation.validate(ROOT), [])
        self.assertEqual(compute.call_count, 2)

    def test_cache_key_separates_identity_phase_and_mutations(self) -> None:
        checkpoint = self._checkpoint()
        compute = mock.Mock(return_value=None)

        def fake_validate(*args: object, **kwargs: object) -> list[str]:
            del args, kwargs
            self._cached_source(checkpoint)
            self._cached_source(checkpoint)
            self._cached_source(copy.deepcopy(checkpoint))
            self._cached_source(checkpoint, root=ROOT / "other-root")
            self._cached_source(checkpoint, historical=True)
            history = checkpoint["goal_execution"]["transition_history"]
            history[-1]["event_sha256"] = "5" * 64
            self._cached_source(checkpoint)
            checkpoint["working_tree_snapshot"]["content_set_sha256"] = "6" * 64
            self._cached_source(checkpoint)
            checkpoint["goal_execution"]["goal_status"][
                continuation.FP048_R002_GOAL_ID
            ] = "IN_PROGRESS"
            self._cached_source(checkpoint)
            checkpoint["current_work"]["release_completion_claimed"] = True
            self._cached_source(checkpoint)
            return []

        with (
            mock.patch.object(
                continuation,
                "_compute_fp048_r002_current_descendant_seq96_source",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate",
                side_effect=fake_validate,
            ),
        ):
            self.assertEqual(continuation.validate(ROOT), [])
        self.assertEqual(compute.call_count, 8)

    def test_top_level_and_direct_replay_calls_do_not_share_cache(self) -> None:
        checkpoint = self._checkpoint()
        compute = mock.Mock(return_value=None)

        def exercise() -> list[str]:
            self._cached_source(checkpoint)
            self._cached_source(checkpoint)
            return []

        with (
            mock.patch.object(
                continuation,
                "_compute_fp048_r002_current_descendant_seq96_source",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate",
                side_effect=lambda *args, **kwargs: exercise(),
            ),
        ):
            self.assertEqual(continuation.validate(ROOT), [])
            self.assertEqual(continuation.validate(ROOT), [])
        self.assertEqual(compute.call_count, 2)

        compute.reset_mock()
        with (
            mock.patch.object(
                continuation,
                "_compute_fp048_r002_current_descendant_seq96_source",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate_transition_replay",
                side_effect=lambda *args, **kwargs: exercise(),
            ),
        ):
            self.assertEqual(
                continuation.validate_transition_replay(ROOT, checkpoint, {}),
                [],
            )
            self.assertEqual(
                continuation.validate_transition_replay(ROOT, checkpoint, {}),
                [],
            )
        self.assertEqual(compute.call_count, 2)

    def test_nested_validate_reuses_and_exception_restores_context(self) -> None:
        checkpoint = self._checkpoint()
        compute = mock.Mock(return_value=None)
        depth = 0

        def fake_validate(*args: object, **kwargs: object) -> list[str]:
            nonlocal depth
            del args, kwargs
            self._cached_source(checkpoint)
            if depth == 0:
                depth += 1
                try:
                    self.assertEqual(continuation.validate(ROOT), [])
                finally:
                    depth -= 1
            self._cached_source(checkpoint)
            return []

        with (
            mock.patch.object(
                continuation,
                "_compute_fp048_r002_current_descendant_seq96_source",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate",
                side_effect=fake_validate,
            ),
        ):
            self.assertEqual(continuation.validate(ROOT), [])
        compute.assert_called_once()

        with mock.patch.object(
            continuation,
            "_validate",
            side_effect=RuntimeError("validator failed"),
        ):
            with self.assertRaisesRegex(RuntimeError, "validator failed"):
                continuation.validate(ROOT)
        self.assertIsNone(
            continuation._FP048_R002_CONTINUATION_CALL_CACHE.get()
        )

    def test_successor_result_is_cached_as_immutable_data(self) -> None:
        checkpoint = self._checkpoint()
        compute = mock.Mock(return_value=["sentinel"])

        def fake_validate(*args: object, **kwargs: object) -> list[str]:
            del args, kwargs
            first = continuation.validate_fp048_r002_successor_seq99_102(
                checkpoint,
                root=ROOT,
            )
            first.append("caller mutation")
            self.assertEqual(
                continuation.validate_fp048_r002_successor_seq99_102(
                    checkpoint,
                    root=ROOT,
                ),
                ["sentinel"],
            )
            self.assertEqual(
                continuation.validate_fp048_r002_successor_seq99_102(
                    checkpoint,
                    root=ROOT,
                ),
                ["sentinel"],
            )
            self.assertEqual(
                continuation.validate_fp048_r002_successor_seq99_102(
                    checkpoint,
                    root=ROOT,
                    require_live_snapshot=False,
                ),
                ["sentinel"],
            )
            return []

        with (
            mock.patch.object(
                continuation,
                "_compute_validate_fp048_r002_successor_seq99_102",
                compute,
            ),
            mock.patch.object(
                continuation,
                "_validate",
                side_effect=fake_validate,
            ),
        ):
            self.assertEqual(continuation.validate(ROOT), [])
        self.assertEqual(compute.call_count, 2)


class WalkSafeFp048R002SuccessorSeq99Seq102Test(unittest.TestCase):
    @classmethod
    def _checkpoint(cls, length: int) -> dict[str, object]:
        history: list[dict[str, object]] = [
            {"sequence": sequence, "event_id": f"SYNTHETIC-{sequence}"}
            for sequence in range(1, length + 1)
        ]
        history[97] = {
            "sequence": 98,
            "event_id": continuation.FP048_R002_R009_EXECUTION_CORRECTION_EVENT_ID,
            "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
            "subject_goal_id": continuation.FP048_R002_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
        }
        if length >= 99:
            history[98] = {
                "sequence": 99,
                "event_id": continuation.FP048_R002_SUCCESSOR_CORRECTION_EVENT_ID,
                "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
                "subject_goal_id": continuation.FP048_R002_GOAL_ID,
                "from_status": "READY",
                "to_status": "READY",
                "status_changes": {},
            }
        if length >= 100:
            history[99] = {
                "sequence": 100,
                "event_id": continuation.FP048_R002_SUCCESSOR_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "subject_goal_id": continuation.FP048_R002_GOAL_ID,
                "from_status": "READY",
                "to_status": "IN_PROGRESS",
                "status_changes": {
                    continuation.FP048_R002_GOAL_ID: "IN_PROGRESS"
                },
            }
        if length >= 101:
            history[100] = {
                "sequence": 101,
                "event_id": continuation.FP048_R002_SUCCESSOR_EVIDENCE_EVENT_ID,
                "event_type": "CANONICAL_BINDINGS_UPDATED",
                "produced_by_goal_id": continuation.FP048_R002_GOAL_ID,
                "from_status": "IN_PROGRESS",
                "to_status": "IN_PROGRESS",
                "status_changes": {},
            }
        if length >= 102:
            history[101] = {
                "sequence": 102,
                "event_id": continuation.FP048_R002_SUCCESSOR_COMPLETION_EVENT_ID,
                "event_type": "GOAL_COMPLETED",
                "subject_goal_id": continuation.FP048_R002_GOAL_ID,
                "from_status": "IN_PROGRESS",
                "to_status": "COMPLETE_AT_TARGET",
                "status_changes": {
                    continuation.FP048_R002_GOAL_ID: "COMPLETE_AT_TARGET"
                },
            }
        return {"goal_execution": {"transition_history": history}}

    @staticmethod
    def _raw(checkpoint: dict[str, object]) -> bytes:
        return (
            json.dumps(checkpoint, ensure_ascii=False, indent=2) + "\n"
        ).encode("utf-8")

    def _authorities(self) -> tuple[mock.Mock, mock.Mock, mock.Mock]:
        correction = mock.Mock()
        correction.reconstructed_seq98_checkpoint_bytes.return_value = self._raw(
            self._checkpoint(98)
        )
        starter = mock.Mock()
        starter.reconstructed_seq99_checkpoint_bytes.return_value = self._raw(
            self._checkpoint(99)
        )
        completion = mock.Mock()
        completion.reconstructed_seq100_checkpoint_bytes.return_value = self._raw(
            self._checkpoint(100)
        )
        return correction, starter, completion

    def test_seq99_seq100_and_adjacent_seq102_are_dispatched(self) -> None:
        for length in (99, 100, 102):
            with self.subTest(length=length):
                correction, starter, completion = self._authorities()
                with (
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_successor_correction_authority",
                        return_value=correction,
                    ),
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_successor_started_authority",
                        return_value=starter,
                    ),
                    mock.patch.object(
                        continuation,
                        "_fp048_r002_successor_completion_authority",
                        return_value=completion,
                    ),
                ):
                    self.assertEqual(
                        continuation.validate_fp048_r002_successor_seq99_102(
                            self._checkpoint(length),
                            require_live_snapshot=True,
                        ),
                        [],
                    )
                correction.require_start_gate_execution_corrected_checkpoint.assert_called_once()
                self.assertEqual(
                    correction.require_start_gate_execution_corrected_checkpoint.call_args.kwargs[
                        "require_live_snapshot"
                    ],
                    length == 99,
                )
                self.assertEqual(
                    starter.require_started_checkpoint.call_count,
                    int(length >= 100),
                )
                if length >= 100:
                    self.assertEqual(
                        starter.require_started_checkpoint.call_args.kwargs[
                            "require_live_snapshot"
                        ],
                        length == 100,
                    )
                self.assertEqual(
                    completion.require_completed_checkpoint.call_count,
                    int(length == 102),
                )
                if length == 102:
                    self.assertTrue(
                        completion.require_completed_checkpoint.call_args.kwargs[
                            "require_live_snapshot"
                        ]
                    )

    def test_projected_seq102_uses_nonlive_chain_and_live_drift_fails(self) -> None:
        correction, starter, completion = self._authorities()
        checkpoint = self._checkpoint(102)
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=False,
            ),
        ):
            self.assertEqual(
                continuation.validate_fp048_r002_successor_seq99_102(
                    checkpoint,
                ),
                [],
            )
        for validator in (
            correction.require_start_gate_execution_corrected_checkpoint,
            starter.require_started_checkpoint,
            completion.require_completed_checkpoint,
        ):
            self.assertFalse(
                validator.call_args.kwargs["require_live_snapshot"]
            )

        completion.require_completed_checkpoint.side_effect = RuntimeError(
            "live checkpoint drift"
        )
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=True,
            ),
        ):
            errors = continuation.validate_fp048_r002_successor_seq99_102(
                checkpoint,
            )
        self.assertTrue(any("live checkpoint drift" in error for error in errors))

    def test_explicit_false_cannot_downgrade_live_and_io_fails_closed(self) -> None:
        checkpoint = self._checkpoint(99)
        correction, starter, completion = self._authorities()
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_correction_authority",
                return_value=correction,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_started_authority",
                return_value=starter,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_completion_authority",
                return_value=completion,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_successor_is_live_checkpoint",
                return_value=True,
            ),
        ):
            self.assertEqual(
                continuation.validate_fp048_r002_successor_seq99_102(
                    checkpoint,
                    require_live_snapshot=False,
                ),
                [],
            )
        self.assertTrue(
            correction.require_start_gate_execution_corrected_checkpoint
            .call_args.kwargs["require_live_snapshot"]
        )

        with mock.patch.object(
            continuation,
            "_fp048_r002_successor_is_live_checkpoint",
            side_effect=ValueError("live checkpoint cannot be read"),
        ), mock.patch.object(
            continuation.importlib,
            "import_module",
            side_effect=AssertionError("authority load must remain fail-closed"),
        ):
            errors = continuation.validate_fp048_r002_successor_seq99_102(
                checkpoint,
                require_live_snapshot=False,
            )
        self.assertTrue(
            any("live checkpoint cannot be read" in error for error in errors)
        )

    def test_seq101_without_adjacent_seq102_is_rejected_before_import(self) -> None:
        with mock.patch.object(
            continuation.importlib,
            "import_module",
            side_effect=AssertionError("successor authority must stay lazy"),
        ):
            errors = continuation.validate_fp048_r002_successor_seq99_102(
                self._checkpoint(101)
            )
        self.assertTrue(any("lacks adjacent seq102" in error for error in errors))

    def test_bool_sequence_and_wrong_identity_are_rejected(self) -> None:
        mutations = {
            "bool sequence": {"sequence": True},
            "wrong event ID": {"event_id": "FORGED-SEQ99"},
            "wrong event type": {"event_type": "GOAL_STARTED"},
        }
        for label, mutation in mutations.items():
            with self.subTest(label=label):
                checkpoint = self._checkpoint(99)
                checkpoint["goal_execution"]["transition_history"][-1].update(
                    mutation
                )
                errors = continuation.validate_fp048_r002_successor_seq99_102(
                    checkpoint
                )
                self.assertTrue(errors)
                self.assertIn("identity or status differs", errors[0])

    def test_failed_r010_direct_seq99_is_rejected(self) -> None:
        checkpoint = self._checkpoint(99)
        checkpoint["goal_execution"]["transition_history"][-1].update(
            {
                "event_id": continuation.FP048_R002_R010_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        )
        errors = continuation.validate_fp048_r002_successor_seq99_102(checkpoint)
        self.assertTrue(any("legacy failed R010" in error for error in errors))

    def test_historical_seq98_does_not_import_successor_modules(self) -> None:
        with mock.patch.object(
            continuation.importlib,
            "import_module",
            side_effect=AssertionError("historical seq98 must remain isolated"),
        ):
            self.assertEqual(
                continuation.validate_fp048_r002_successor_seq99_102(
                    self._checkpoint(98)
                ),
                [],
            )

    def test_authority_loaders_import_only_the_exact_successor_modules(self) -> None:
        modules = {
            continuation.FP048_R002_SUCCESSOR_CORRECTION_MODULE: mock.Mock(),
            continuation.FP048_R002_SUCCESSOR_STARTED_MODULE: mock.Mock(),
            continuation.FP048_R002_SUCCESSOR_COMPLETION_MODULE: mock.Mock(),
        }
        with mock.patch.object(
            continuation.importlib,
            "import_module",
            side_effect=modules.__getitem__,
        ) as importer:
            self.assertIs(
                continuation._fp048_r002_successor_correction_authority(),
                modules[continuation.FP048_R002_SUCCESSOR_CORRECTION_MODULE],
            )
            self.assertIs(
                continuation._fp048_r002_successor_started_authority(),
                modules[continuation.FP048_R002_SUCCESSOR_STARTED_MODULE],
            )
            self.assertIs(
                continuation._fp048_r002_successor_completion_authority(),
                modules[continuation.FP048_R002_SUCCESSOR_COMPLETION_MODULE],
            )
        self.assertEqual(
            importer.call_args_list,
            [
                mock.call(continuation.FP048_R002_SUCCESSOR_CORRECTION_MODULE),
                mock.call(continuation.FP048_R002_SUCCESSOR_STARTED_MODULE),
                mock.call(continuation.FP048_R002_SUCCESSOR_COMPLETION_MODULE),
            ],
        )

    def test_completion_producer_hook_uses_the_successor_dispatch(self) -> None:
        checkpoint = self._checkpoint(102)
        with mock.patch.object(
            continuation,
            "validate_fp048_r002_successor_seq99_102",
            return_value=["sentinel"],
        ) as validator:
            self.assertEqual(
                continuation.validate_fp048_r002_completion_seq101_102(
                    checkpoint,
                    root=ROOT,
                ),
                ["sentinel"],
            )
        validator.assert_called_once_with(
            checkpoint,
            root=ROOT,
            require_live_snapshot=False,
        )


class WalkSafeFp048R002CompletionSeq100Seq101Test(unittest.TestCase):
    @staticmethod
    def _checkpoint(length: int) -> dict[str, object]:
        history = [
            {"sequence": sequence, "event_id": f"SYNTHETIC-{sequence}"}
            for sequence in range(1, length + 1)
        ]
        if length >= 99:
            history[98] = {
                "sequence": 99,
                "event_id": continuation.FP048_R002_R010_STARTED_EVENT_ID,
                "event_type": "GOAL_STARTED",
            }
        if length >= 100:
            history[99] = {
                "sequence": 100,
                "event_id": continuation.FP048_R002_COMPLETION_EVIDENCE_EVENT_ID,
                "event_type": "CANONICAL_BINDINGS_UPDATED",
            }
        if length >= 101:
            history[100] = {
                "sequence": 101,
                "event_id": continuation.FP048_R002_COMPLETION_EVENT_ID,
                "event_type": "GOAL_COMPLETED",
            }
        return {"goal_execution": {"transition_history": history}}

    def test_seq100_without_adjacent_seq101_is_rejected(self) -> None:
        self.assertEqual(
            continuation.validate_fp048_r002_completion_seq100_101(
                self._checkpoint(100)
            ),
            [
                "FP048 R002 seq100 producer transaction lacks adjacent "
                "seq101 completion"
            ],
        )

    def test_completion_loader_pins_receipt_and_review_paths(self) -> None:
        authority = mock.Mock()
        authority.SOURCE_SEQUENCE = continuation.FP048_R002_COMPLETION_SOURCE_SEQUENCE
        authority.EVIDENCE_SEQUENCE = continuation.FP048_R002_COMPLETION_EVIDENCE_SEQUENCE
        authority.COMPLETION_SEQUENCE = continuation.FP048_R002_COMPLETION_SEQUENCE
        authority.EVIDENCE_EVENT_ID = continuation.FP048_R002_COMPLETION_EVIDENCE_EVENT_ID
        authority.COMPLETION_EVENT_ID = continuation.FP048_R002_COMPLETION_EVENT_ID
        authority.COMPLETION_RECEIPT_REL = continuation.FP048_R002_COMPLETION_RECEIPT_REL
        authority.REVIEW_ROOT = continuation.FP048_R002_COMPLETION_REVIEW_ROOT
        with mock.patch.object(
            continuation.importlib,
            "import_module",
            return_value=authority,
        ):
            self.assertIs(continuation._fp048_r002_completion_authority(), authority)
            authority.COMPLETION_RECEIPT_REL = Path("forged-receipt.json")
            with self.assertRaisesRegex(RuntimeError, "constant differs"):
                continuation._fp048_r002_completion_authority()

    def test_exact_seq101_uses_public_inverse_and_projection_authority(self) -> None:
        checkpoint = self._checkpoint(101)
        source = self._checkpoint(99)
        source_raw = (json.dumps(source, sort_keys=True) + "\n").encode()
        authority = mock.Mock()
        authority.reconstructed_seq99_checkpoint_bytes.return_value = source_raw
        authority.checkpoint_bytes.side_effect = (
            lambda value: (json.dumps(value, sort_keys=True) + "\n").encode()
        )
        authority.validate_projection.return_value = None
        started = mock.Mock()
        started.require_started_checkpoint.return_value = None
        evidence = object()
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_completion_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r010_started_authority",
                return_value=started,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_completion_evidence_seq100_101",
                return_value=evidence,
            ),
        ):
            errors = continuation.validate_fp048_r002_completion_seq100_101(
                checkpoint
            )
        self.assertEqual(errors, [])
        authority.reconstructed_seq99_checkpoint_bytes.assert_called_once_with(
            ROOT,
            checkpoint,
        )
        started.require_started_checkpoint.assert_called_once_with(
            ROOT,
            source,
            require_live_snapshot=False,
            run_external_validators=False,
        )
        authority.validate_projection.assert_called_once_with(
            ROOT,
            source,
            checkpoint,
            evidence,
        )

    def test_seq101_projection_tamper_fails_closed(self) -> None:
        checkpoint = self._checkpoint(101)
        source = self._checkpoint(99)
        source_raw = (json.dumps(source, sort_keys=True) + "\n").encode()
        authority = mock.Mock()
        authority.reconstructed_seq99_checkpoint_bytes.return_value = source_raw
        authority.checkpoint_bytes.side_effect = (
            lambda value: (json.dumps(value, sort_keys=True) + "\n").encode()
        )
        authority.validate_projection.side_effect = RuntimeError(
            "seq100/101 event seal differs"
        )
        started = mock.Mock()
        with (
            mock.patch.object(
                continuation,
                "_fp048_r002_completion_authority",
                return_value=authority,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_r010_started_authority",
                return_value=started,
            ),
            mock.patch.object(
                continuation,
                "_fp048_r002_completion_evidence_seq100_101",
                return_value=object(),
            ),
        ):
            errors = continuation.validate_fp048_r002_completion_seq100_101(
                checkpoint
            )
        self.assertTrue(any("event seal differs" in row for row in errors))


if __name__ == "__main__":
    unittest.main()
