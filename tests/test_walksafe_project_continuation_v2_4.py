"""Focused lifecycle and frozen-predecessor regressions for Goal v2.4."""

from __future__ import annotations

import copy
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
    def _checkpoint_with_current_latest_canonical_bindings() -> dict:
        checkpoint = continuation.load_json(CHECKPOINT)
        state = checkpoint["goal_execution"]
        history = state["transition_history"]
        tail = history[-1]
        snapshot = continuation.canonical_binding_snapshot(checkpoint)
        top_level_by_role = {
            binding["role"]: binding
            for binding in checkpoint["canonical_bindings"]
        }
        for role, binding in snapshot.items():
            digest = sha256_file(ROOT / binding["path"])
            binding["file_sha256"] = digest
            top_level_by_role[role]["file_sha256"] = digest
        tail["canonical_binding_snapshot_after"] = snapshot
        tail["event_sha256"] = continuation.event_sha256(tail)
        state["transition_history_anchor_sha256"] = tail["event_sha256"]
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
            sequence=72, event_id="WS-SYNTHETIC-CLOSURE-072", event_type="CANONICAL_BINDINGS_UPDATED",
            previous_event_sha256=history[-1]["event_sha256"], subject_goal_id="WS-GOAL-EPIC-03",
            produced_by_goal_id=None, produced_binding_roles=[],
            producer_completion_receipt_binding=None,
            producer_output_subject_ids_by_role={},
            canonical_binding_snapshot_after=continuation.canonical_binding_snapshot(checkpoint),
            changed_binding_roles=[], changed_subject_ids_by_role={}, impact_closure_goal_ids=[],
            impact_disposition_by_goal={"WS-GOAL-EPIC-03": {"result": "REOPEN_CONTAINER", "target_status": "PLANNED"}},
            reopened_completion_event_sha256_by_goal={"WS-GOAL-EPIC-03": "e" * 64}, status_changes={"WS-GOAL-EPIC-03": "PLANNED"},
            from_status="COMPLETE_AT_TARGET", to_status="PLANNED",
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
        legacy_members = latest_children[fixture["epic"]]
        self.assertNotEqual(legacy_members, sorted(legacy_members))
        successors = {
            fixture["fp_next"]: "f" * 64,
            fixture["npc_next"]: "d" * 64,
        }

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

        control_context = r008_control_review.prepare_control_successor_r011_context(
            cls.root
        )
        control_assignment_raw = (
            r008_control_review.build_control_successor_r011_assignment(
                control_context, assigned_at=assigned_at
            ).encode("utf-8")
        )
        control_assignment = json.loads(control_assignment_raw)
        control_result = {
            "schema_version": "1.0",
            "evidence_type": (
                "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_"
                "REVIEW_RESULT"
            ),
            "goal_id": r008_control_review.GOAL_ID,
            "round_id": r008_control_review.CONTROL_SUCCESSOR_R011_ROUND_ID,
            "reviewed_at": assigned_at,
            "reviewer": copy.deepcopy(control_assignment["reviewer"]),
            "assignment_binding": r008_control_review._binding(
                r008_control_review.CONTROL_SUCCESSOR_R011_ASSIGNMENT_REL,
                control_assignment_raw,
            ),
            "review_scope": copy.deepcopy(control_assignment["review_scope"]),
            "decision": "APPROVED",
            "findings": {"blocking": [], "major_open": [], "minor_open": []},
            "finding_dispositions": [],
            "review_boundary": copy.deepcopy(
                control_assignment["review_boundary"]
            ),
        }
        control_result_raw = r008_control_review.json_text(
            control_result
        ).encode("utf-8")
        control_independent_raw = (
            r008_control_review.build_control_successor_r011_independent_review(
                control_context,
                control_assignment,
                control_assignment_raw,
                control_result,
                control_result_raw,
            ).encode("utf-8")
        )
        for relative, raw in (
            (
                r008_control_review.CONTROL_SUCCESSOR_R011_ASSIGNMENT_REL,
                control_assignment_raw,
            ),
            (
                r008_control_review.CONTROL_SUCCESSOR_R011_RESULT_REL,
                control_result_raw,
            ),
            (
                r008_control_review.CONTROL_SUCCESSOR_R011_INDEPENDENT_REL,
                control_independent_raw,
            ),
        ):
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)

        r011_binding, r011_raw = (
            r008_review.load_validated_control_successor_r011(cls.root)
        )
        r010_binding, r010_raw = (
            r008_review.load_frozen_control_successor_r010(cls.root)
        )
        r009_binding, r009_raw = (
            r008_review.load_frozen_control_successor_r009(cls.root)
        )

        assignment_raw = r008_review.build_transition_assignment(
            cls.root, assigned_at=assigned_at
        ).encode("utf-8")
        assignment = json.loads(assignment_raw)
        result = {
            "schema_version": "1.0",
            "evidence_type": (
                "FP046_NPC_R002_REOPEN_TRANSITION_REVIEWER_AUTHORED_RESULT"
            ),
            "goal_id": r008_review.TRANSITION_GOAL_ID,
            "round_id": r008_review.TRANSITION_ROUND_ID,
            "reviewed_at": assigned_at,
            "reviewer": copy.deepcopy(assignment["reviewer"]),
            "assignment_binding": r008_review._binding(
                r008_review.TRANSITION_ASSIGNMENT_REL, assignment_raw
            ),
            "decision": "APPROVED",
            "findings": {"blocking": [], "major_open": [], "minor_open": []},
            "finding_dispositions": [],
            "review_scope": copy.deepcopy(assignment["review_scope"]),
            "review_boundary": copy.deepcopy(assignment["review_boundary"]),
        }
        result_raw = r008_review.json_text(result).encode("utf-8")
        independent_raw = r008_review.build_transition_independent_review(
            assignment, assignment_raw, result, result_raw
        ).encode("utf-8")
        review_overlay = {
            r008_review.TRANSITION_R004_ASSIGNMENT_REL: assignment_raw,
            r008_review.TRANSITION_R004_RESULT_REL: result_raw,
            r008_review.TRANSITION_R004_INDEPENDENT_REL: independent_raw,
        }
        for relative, raw in review_overlay.items():
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        r004_binding, r004_raw = r008_review.load_validated_transition_r004(
            cls.root
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
        transaction = r008_transaction.build_transaction(cls.root)
        cls.transaction = transaction
        cls.checkpoint = transaction["checkpoint"]
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
                path: "current R004 transition review"
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
                path: "current R011 control review"
                for path in r011_raw
            },
        }
        cls.authorization_sha256 = cls.checkpoint["goal_execution"][
            "transition_history"
        ][1]["package_activation_authorization_binding"]["file_sha256"]
        for relative, raw in {
            **transaction["output_bytes"],
        }.items():
            target = cls.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)

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
        seq77 = {
            **copy.deepcopy(common),
            "sequence": 77,
            "event_id": (
                "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
                "FP046-R002-20260823-001"
            ),
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_at": "2026-08-23T12:00:00+09:00",
            "previous_event_sha256": seq76["event_sha256"],
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
        }
        seq77["event_sha256"] = continuation.event_sha256(seq77)
        seq78 = {
            **copy.deepcopy(common),
            "sequence": 78,
            "event_id": (
                "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
                "FP046-R002-20260823-001"
            ),
            "event_type": "GOAL_STARTED",
            "occurred_at": "2026-08-23T12:00:01+09:00",
            "previous_event_sha256": seq77["event_sha256"],
            "from_status": "READY",
            "to_status": "IN_PROGRESS",
            "status_changes": {r008_review.FP046_R002: "IN_PROGRESS"},
        }
        seq78["event_sha256"] = continuation.event_sha256(seq78)
        history.extend((seq77, seq78))
        state["status_by_goal"][r008_review.FP046_R002] = "IN_PROGRESS"
        state["transition_history_anchor_sha256"] = seq78["event_sha256"]
        state["validation_cutoff_at"] = seq78["occurred_at"]

    @classmethod
    def _validate_boundary_with_live_checkpoint(
        cls,
        checkpoint: dict[str, object],
    ) -> list[str]:
        path = cls.root / r008_review.CHECKPOINT_REL
        original = path.read_bytes()
        try:
            path.write_bytes(r008_review.json_text(checkpoint).encode("utf-8"))
            r008_review.load_validated_transition_r004(cls.root)
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
        neutral = r008_review.approval_neutral_plan_core(
            self.transaction["preflight"]
        )
        seq72 = neutral["events"][0]

        self.assertNotIn("transition_review_binding", seq72)
        self.assertNotIn("transition_review_subject_binding", seq72)
        for field in (
            "predecessor_transition_review_binding",
            "r009_control_review_binding",
            "r010_control_review_binding",
            "r011_control_review_binding",
        ):
            self.assertEqual(
                seq72[field],
                self.event_review_binding_by_field[field],
            )
        self.assertEqual(
            r008_review.transition_plan_core_binding(neutral),
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
                "current R004 transition review",
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
                "current R011 control review",
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
            "sequence": 79,
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
            "sequence": 79,
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
        seq78 = state["transition_history"][77]
        seq78["status_changes"][r008_review.NPC_R002] = "READY"
        state["status_by_goal"][r008_review.NPC_R002] = "READY"
        self._reseal_from(checkpoint, 77)

        self.assertIn(
            "post-seq76 status transition is ambiguous: 78",
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
            "inventory": "ordinary Goal inventory projection differs",
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


if __name__ == "__main__":
    unittest.main()
