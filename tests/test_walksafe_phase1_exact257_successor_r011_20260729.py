from __future__ import annotations

from collections import Counter
from contextlib import redirect_stderr
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import build_walksafe_phase1_exact257_successor_r011_20260729 as builder


def _json(content: bytes) -> dict:
    return json.loads(content.decode("utf-8"), object_pairs_hook=builder._strict_object)


class WalkSafePhase1Exact257SuccessorR011Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source_state = builder._load_source_state()
        cls.outputs = builder._build_outputs()
        cls.r007 = cls.source_state["r007"]
        cls.r011 = _json(cls.outputs[builder.R011_LEDGER_PATH])

    def _reseal_output_graph(self, ledger: dict) -> dict[Path, bytes]:
        ledger_bytes = builder._seal_json(ledger, builder.R011_LEDGER_PATH)
        evidence = _json(self.outputs[builder.R011_EVIDENCE_PATH])
        evidence["subject_chain"]["r011_ledger"] = builder._bytes_binding(
            builder.R011_LEDGER_PATH,
            ledger_bytes,
            "R011-OUT-001",
            "R011_FULL_EXACT257_LEDGER",
        )
        evidence_bytes = builder._seal_json(evidence, builder.R011_EVIDENCE_PATH)
        receipt = _json(self.outputs[builder.R011_RECEIPT_PATH])
        receipt["output_bindings"] = [
            builder._bytes_binding(
                builder.R011_LEDGER_PATH,
                ledger_bytes,
                "R011-OUT-001",
                "R011_FULL_EXACT257_LEDGER",
            ),
            builder._bytes_binding(
                builder.R011_EVIDENCE_PATH,
                evidence_bytes,
                "R011-OUT-002",
                "R011_READY25_PROGRESS_EVIDENCE",
            ),
        ]
        receipt_bytes = builder._seal_json(receipt, builder.R011_RECEIPT_PATH)
        return {
            builder.R011_LEDGER_PATH: ledger_bytes,
            builder.R011_EVIDENCE_PATH: evidence_bytes,
            builder.R011_RECEIPT_PATH: receipt_bytes,
        }

    def test_outputs_are_exact_deterministic_and_final_lf(self) -> None:
        self.assertEqual(
            set(self.outputs),
            {
                builder.R011_LEDGER_PATH,
                builder.R011_EVIDENCE_PATH,
                builder.R011_RECEIPT_PATH,
            },
        )
        self.assertEqual(self.outputs, builder._build_outputs())
        for path, content in self.outputs.items():
            self.assertTrue(content.endswith(b"\n"), path)
            self.assertFalse(content.endswith(b"\n\n"), path)
            self.assertFalse(content.endswith(b"\r\n"), path)
        for path in (
            builder.R011_LEDGER_PATH,
            builder.R011_EVIDENCE_PATH,
            builder.R011_RECEIPT_PATH,
        ):
            builder._validate_nonself(_json(self.outputs[path]), path)
        evidence = _json(self.outputs[builder.R011_EVIDENCE_PATH])
        review_boundary = evidence["document_review_boundary"]
        self.assertFalse(
            review_boundary["r011_independent_review_generated_by_builder"]
        )
        self.assertEqual(
            review_boundary["r011_independent_review_status_at_generation"],
            "PENDING_SEPARATE_POST_MATERIALIZATION_REVIEW",
        )
        self.assertNotIn(builder.R011_REVIEW_PATH, self.outputs)

    def test_nonself_metadata_contract_is_strict(self) -> None:
        mutations = {
            "algorithm": lambda section: section.update({"algorithm": "MD5"}),
            "ensure_ascii_alias": lambda section: section.update({"ensure_ascii": 0}),
            "separators": lambda section: section.update(
                {"json_separators": [", ", ": "]}
            ),
            "extra_field": lambda section: section.update({"undeclared": "value"}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                document = _json(self.outputs[builder.R011_LEDGER_PATH])
                mutate(document["integrity"])
                with self.assertRaises(builder.ValidationError):
                    builder._validate_nonself(document, builder.R011_LEDGER_PATH)

    def test_exact_sets_are_disjoint_and_have_pinned_fingerprints(self) -> None:
        self.assertEqual(len(builder.EXACT9), 9)
        self.assertEqual(len(builder.EXACT13), 13)
        self.assertEqual(len(builder.EXACT3), 3)
        self.assertFalse(set(builder.EXACT9) & set(builder.EXACT13))
        self.assertFalse(set(builder.EXACT9) & set(builder.EXACT3))
        self.assertFalse(set(builder.EXACT13) & set(builder.EXACT3))
        self.assertEqual(len(builder.EXACT25_SET), 25)
        fingerprints = builder._exact_fingerprints()
        self.assertEqual(
            fingerprints["exact9_set"]["sha256"],
            "f9ad54b016fd88acd9902bedb5b72d069c40976f0c5bf406bfd72c9d7f563703",
        )
        self.assertEqual(
            fingerprints["exact13_set"]["sha256"],
            "20a267bf37e7626a33ffe2751a136c8d562ca61d606065ed3fe6ab97dade31a5",
        )
        self.assertEqual(
            fingerprints["exact3_set"]["sha256"],
            "cbf82d6ed916977579682552299668ca2e552a22a0c55284c2a7ffe564fb631e",
        )
        self.assertEqual(
            fingerprints["exact25_set"]["sha256"],
            "c952cc7fc5d6c440444ab27e121b463a4db23fc1990b9bdbba38f6421baaf948",
        )

    def test_exact257_counts_and_completion_boolean_totality_are_preserved(self) -> None:
        rows = builder._record_by_id(self.r011)
        self.assertEqual(len(rows), 257)
        self.assertEqual(
            self.r011["summaries"]["canonical_status_counts"],
            builder.CANONICAL_STATUS_COUNTS,
        )
        self.assertEqual(
            self.r011["summaries"]["current_queue_route_counts"],
            builder.QUEUE_ROUTE_COUNTS,
        )
        self.assertEqual(self.r011["summaries"]["open_artifact_count"], 131)
        self.assertEqual(
            self.r011["summaries"]["current_scope_closure_delta_count"], 2
        )
        current_scope_true = []
        for artifact_id, row in rows.items():
            for group, field in builder.SIX_COMPLETION_PATHS:
                self.assertIs(type(row[group][field]), bool, (artifact_id, group, field))
            if row["artifact_closure"]["current_scope_n_a_closure_claimed"]:
                current_scope_true.append(artifact_id)
            self.assertFalse(row["artifact_closure"]["completion_claimed"])
            self.assertFalse(
                row["artifact_closure"]["global_artifact_completion_claimed"]
            )
            self.assertFalse(row["claim_boundary"]["artifact_completion_claimed"])
            self.assertFalse(
                row["claim_boundary"]["global_artifact_completion_claimed"]
            )
            self.assertFalse(row["release_eligibility"]["eligible"])
            self.assertEqual(
                row["release_eligibility"]["status"], "NOT_ELIGIBLE"
            )
        self.assertEqual(sorted(current_scope_true), ["DLV-DSC-04", "DLV-WS-16"])

    def test_r011_changes_only_three_progress_appends_for_exact25(self) -> None:
        self.assertEqual(
            [
                row["artifact_type_code"]
                for row in self.r011["records"]
            ],
            [
                row["artifact_type_code"]
                for row in self.r007["records"]
            ],
        )
        before_rows = builder._record_by_id(self.r007)
        after_rows = builder._record_by_id(self.r011)
        unchanged = 0
        changed = 0
        for artifact_id, before in before_rows.items():
            after = after_rows[artifact_id]
            if artifact_id not in builder.EXACT25_SET:
                self.assertEqual(after, before, artifact_id)
                unchanged += 1
                continue
            expected = deepcopy(before)
            entries = builder._progress_entries(artifact_id)
            expected["progress_axes"]["content_authored"]["observations"].append(
                entries["content_authored"]
            )
            expected["progress_axes"]["packet_materialization"].append(
                entries["packet_materialization"]
            )
            expected["progress_axes"]["independent_review"].append(
                entries["independent_review"]
            )
            self.assertEqual(after, expected, artifact_id)
            self.assertEqual(
                after["progress_axes"]["content_authored"]["observations"][:-1],
                before["progress_axes"]["content_authored"]["observations"],
                artifact_id,
            )
            self.assertEqual(
                after["progress_axes"]["packet_materialization"][:-1],
                before["progress_axes"]["packet_materialization"],
                artifact_id,
            )
            self.assertEqual(
                after["progress_axes"]["independent_review"][:-1],
                before["progress_axes"]["independent_review"],
                artifact_id,
            )
            changed += 1
        self.assertEqual((unchanged, changed), (232, 25))
        totals = (
            sum(
                len(row["progress_axes"]["content_authored"]["observations"])
                for row in after_rows.values()
            ),
            sum(
                len(row["progress_axes"]["packet_materialization"])
                for row in after_rows.values()
            ),
            sum(
                len(row["progress_axes"]["independent_review"])
                for row in after_rows.values()
            ),
        )
        self.assertEqual(totals, (171, 171, 105))

    def test_ready25_progress_entries_are_non_promoting(self) -> None:
        rows = builder._record_by_id(self.r011)
        for artifact_id in builder.EXACT25:
            row = rows[artifact_id]
            content = row["progress_axes"]["content_authored"]["observations"][-1]
            materialization = row["progress_axes"]["packet_materialization"][-1]
            review = row["progress_axes"]["independent_review"][-1]
            self.assertFalse(content["artifact_content_accepted"])
            self.assertFalse(content["completion_claimed"])
            self.assertFalse(content["owner_approved"])
            self.assertFalse(materialization["state_promotion"])
            self.assertEqual(
                review["status"], "REVIEW_OBSERVATION_BOUND_NO_STATE_PROMOTION"
            )
            self.assertEqual(
                review["product_independent_qa_reviewer"], "UNASSIGNED"
            )
            self.assertFalse(review["document_review_substitutes_product_qa"])
            self.assertFalse(review["reviewer_identity_recorded"])
            self.assertFalse(review["review_independence_verified_by_r011"])
            for field in (
                "product_qa_acceptance_credit",
                "closure_credit",
                "execution_credit",
                "owner_approval_credit",
                "release_credit",
            ):
                self.assertIs(type(review[field]), bool)
                self.assertFalse(review[field], (artifact_id, field))
            for group, field in builder.SIX_COMPLETION_PATHS:
                self.assertFalse(row[group][field], (artifact_id, group, field))

    def test_source_physical_bindings_are_current_unique_and_complete(self) -> None:
        evidence = _json(self.outputs[builder.R011_EVIDENCE_PATH])
        bindings = evidence["source_bindings"]
        self.assertEqual(len(bindings), 16)
        self.assertEqual(len({item["binding_id"] for item in bindings}), 16)
        self.assertEqual(len({item["path"] for item in bindings}), 16)
        by_id = {item["binding_id"]: item for item in bindings}
        for binding_id, path, role in builder._source_specs():
            content = path.read_bytes()
            binding = by_id[binding_id]
            self.assertEqual(binding["path"], builder._relative(path))
            self.assertEqual(binding["subject_role"], role)
            self.assertEqual(binding["byte_length"], len(content))
            self.assertEqual(
                binding["sha256"], hashlib.sha256(content).hexdigest()
            )

    def test_source_snapshot_reads_each_physical_source_once(self) -> None:
        real_read = builder._read_confined_file_bytes
        counts: Counter[Path] = Counter()

        def tracking_read(path: Path, allowed_root: Path) -> bytes:
            counts[path] += 1
            return real_read(path, allowed_root)

        with patch.object(
            builder,
            "_read_confined_file_bytes",
            side_effect=tracking_read,
        ):
            source_state = builder._load_source_state()
        self.assertEqual(source_state, self.source_state)
        self.assertEqual(
            counts,
            Counter(path for _, path, _ in builder._source_specs()),
        )

    def test_pinned_source_hash_drift_fails_closed(self) -> None:
        source_bytes = {
            path: path.read_bytes() for _, path, _ in builder._source_specs()
        }
        source_bytes[builder.READY25_CLS_OPS_RECEIPT_PATH] = b"{}\n"
        with self.assertRaises(builder.ValidationError):
            builder._validate_pinned_source_hashes(source_bytes)

    def test_output_binding_graph_uses_current_in_memory_bytes(self) -> None:
        ledger = self.r011
        evidence = _json(self.outputs[builder.R011_EVIDENCE_PATH])
        receipt = _json(self.outputs[builder.R011_RECEIPT_PATH])
        output_paths = {
            builder._relative(builder.R011_LEDGER_PATH),
            builder._relative(builder.R011_EVIDENCE_PATH),
            builder._relative(builder.R011_RECEIPT_PATH),
        }
        self.assertFalse(
            output_paths & {item["path"] for item in ledger["r011_source_bindings"]}
        )
        self.assertEqual(
            evidence["subject_chain"]["r011_ledger"]["sha256"],
            hashlib.sha256(self.outputs[builder.R011_LEDGER_PATH]).hexdigest(),
        )
        self.assertEqual(
            receipt["output_bindings"][1]["sha256"],
            hashlib.sha256(self.outputs[builder.R011_EVIDENCE_PATH]).hexdigest(),
        )

    def test_coordinated_resealed_overclaims_fail_closed(self) -> None:
        mutations = {
            "closure_status": lambda row: row["artifact_closure"].update(
                {"status": "CLOSED", "phase1_closure_delta": True}
            ),
            "formal_claim": lambda row: row["claim_boundary"].update(
                {"formal_pass_claimed": True}
            ),
            "queue_route": lambda row: row["queue_route"].update(
                {"current": "OK_BASELINE"}
            ),
            "content_acceptance": lambda row: row["progress_axes"][
                "content_authored"
            ]["observations"][-1].update({"artifact_content_accepted": True}),
            "state_promotion": lambda row: row["progress_axes"][
                "packet_materialization"
            ][-1].update({"state_promotion": True}),
            "review_credit": lambda row: row["progress_axes"][
                "independent_review"
            ][-1].update({"product_qa_acceptance_credit": True}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                ledger = deepcopy(self.r011)
                row = builder._record_by_id(ledger)["DLV-AIML-19"]
                mutate(row)
                candidate = self._reseal_output_graph(ledger)
                with self.assertRaises(builder.ValidationError):
                    builder._validate_generated_outputs(
                        candidate, self.source_state
                    )

    def test_resealed_record_reordering_fails_closed(self) -> None:
        ledger = deepcopy(self.r011)
        ledger["records"][0], ledger["records"][1] = (
            ledger["records"][1],
            ledger["records"][0],
        )
        candidate = self._reseal_output_graph(ledger)
        with self.assertRaises(builder.ValidationError):
            builder._validate_generated_outputs(candidate, self.source_state)

    def test_strict_boolean_and_integer_aliases_fail_closed(self) -> None:
        mutations = {
            "boolean_as_integer": lambda row: row["progress_axes"][
                "content_authored"
            ]["observations"][-1].update({"artifact_content_accepted": 0}),
            "integer_as_boolean": lambda row: row["progress_axes"][
                "independent_review"
            ][-1].update({"finding_count": False}),
            "integer_as_float": lambda row: row["progress_axes"][
                "independent_review"
            ][-1].update({"finding_count": 0.0}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                ledger = deepcopy(self.r011)
                mutate(builder._record_by_id(ledger)["DLV-AIML-19"])
                candidate = self._reseal_output_graph(ledger)
                with self.assertRaises(builder.ValidationError):
                    builder._validate_generated_outputs(
                        candidate, self.source_state
                    )

    def test_source_overclaim_mutations_fail_closed(self) -> None:
        values = {
            "doc01": builder.load_strict_json(builder.DOC01_PATH),
            "doc05": builder.load_strict_json(builder.DOC05_PATH),
            "manifest": builder.load_strict_json(builder.MANIFEST_PATH),
            "ai_packet": builder.load_strict_json(builder.READY25_AI_PACKET_PATH),
            "ai_receipt": builder.load_strict_json(builder.READY25_AI_RECEIPT_PATH),
            "cls_packet": builder.load_strict_json(
                builder.READY25_CLS_OPS_PACKET_PATH
            ),
            "cls_receipt": builder.load_strict_json(
                builder.READY25_CLS_OPS_RECEIPT_PATH
            ),
            "rel_packet": builder.load_strict_json(builder.READY25_REL_PACKET_PATH),
            "rel_receipt": builder.load_strict_json(
                builder.READY25_REL_RECEIPT_PATH
            ),
        }

        def validate(candidate: dict[str, dict]) -> None:
            builder._validate_ready25_sources(
                candidate["doc01"],
                candidate["doc05"],
                candidate["manifest"],
                candidate["ai_packet"],
                candidate["ai_receipt"],
                candidate["cls_packet"],
                candidate["cls_receipt"],
                candidate["rel_packet"],
                candidate["rel_receipt"],
            )

        def duplicate_doc01_row(value: dict[str, dict]) -> None:
            document = value["doc01"]
            document["artifacts"].append(deepcopy(document["artifacts"][0]))
            body = {
                key: item
                for key, item in document.items()
                if key != "content_sha256"
            }
            document["content_sha256"] = builder._object_sha(body)

        mutations = {
            "doc01_duplicate_row": duplicate_doc01_row,
            "doc01_acceptance": lambda value: next(
                row
                for row in value["doc01"]["artifacts"]
                if row["artifact_type_code"] == "DLV-AIML-19"
            )["state"]["ready25_current_revision"].update(
                {"content_accepted": True}
            ),
            "doc05_approval": lambda value: value["doc05"]["changes"][-1][
                "application"
            ].update({"approval_credit_count": 1}),
            "manifest_release": lambda value: value["manifest"]["metadata"].update(
                {"release_status": "ELIGIBLE"}
            ),
            "exact9_owner_approval": lambda value: value["ai_packet"][
                "credit_summary"
            ].update({"owner_approval_count": 1}),
            "exact13_acceptance": lambda value: value["cls_packet"]["summary"].update(
                {"accepted_count": 1}
            ),
            "exact3_gate_pass": lambda value: value["rel_packet"][
                "five_gate_boundary"
            ][0].update({"status": "PASS"}),
            "numeric_alias_false": lambda value: value["ai_packet"][
                "credit_summary"
            ].update({"owner_approval_count": False}),
            "numeric_alias_float": lambda value: value["cls_packet"][
                "summary"
            ].update({"accepted_count": 0.0}),
            "exact9_empty_receipt_checks": lambda value: value["ai_receipt"].update(
                {"checks": []}
            ),
            "exact13_empty_receipt_checks": lambda value: value[
                "cls_receipt"
            ].update({"checks": []}),
            "exact3_empty_receipt_checks": lambda value: value[
                "rel_receipt"
            ].update({"checks": []}),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                candidate = deepcopy(values)
                mutate(candidate)
                with self.assertRaises(builder.ValidationError):
                    validate(candidate)

    def test_add_only_preflight_rejects_existing_target_without_writes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            paths = [bundle / "ledger.json", bundle / "evidence.json"]
            outputs = {paths[0]: b"ledger\n", paths[1]: b"evidence\n"}
            bundle.mkdir()
            paths[0].write_bytes(b"existing\n")
            with self.assertRaises(FileExistsError):
                builder._write_add_only(outputs, allowed_root=root)
            self.assertEqual(paths[0].read_bytes(), b"existing\n")
            self.assertFalse(paths[1].exists())

    def test_add_only_write_failure_rolls_back_partial_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            paths = [bundle / "ledger.json", bundle / "evidence.json"]
            outputs = {paths[0]: b"ledger\n", paths[1]: object()}
            with self.assertRaises(TypeError):
                builder._write_add_only(  # type: ignore[arg-type]
                    outputs,
                    allowed_root=root,
                )
            self.assertFalse(paths[0].exists())
            self.assertFalse(paths[1].exists())
            self.assertFalse(bundle.exists())

    def test_add_only_keyboard_interrupt_rolls_back_partial_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            paths = [bundle / "ledger.json", bundle / "evidence.json"]
            outputs = {paths[0]: b"ledger\n", paths[1]: b"evidence\n"}
            real_write = builder.os.write
            write_count = 0

            def interrupting_write(descriptor: int, content) -> int:
                nonlocal write_count
                write_count += 1
                if write_count == 2:
                    real_write(descriptor, bytes(content[:1]))
                    raise KeyboardInterrupt
                return real_write(descriptor, content)

            with patch.object(builder.os, "write", side_effect=interrupting_write):
                with self.assertRaises(KeyboardInterrupt):
                    builder._write_add_only(outputs, allowed_root=root)
            self.assertFalse(paths[0].exists())
            self.assertFalse(paths[1].exists())
            self.assertFalse(bundle.exists())

    def test_add_only_abrupt_exit_never_publishes_partial_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            outputs = {
                bundle / "ledger.json": b"ledger\n",
                bundle / "evidence.json": b"evidence\n",
                bundle / "receipt.json": b"receipt\n",
            }
            child_pid = builder.os.fork()
            if child_pid == 0:
                real_write = builder.os.write
                write_count = 0

                def crash_write(descriptor: int, content) -> int:
                    nonlocal write_count
                    write_count += 1
                    if write_count == 2:
                        real_write(descriptor, bytes(content[:1]))
                        builder.os._exit(23)
                    return real_write(descriptor, content)

                builder.os.write = crash_write
                builder._write_add_only(outputs, allowed_root=root)
                builder.os._exit(0)
            _, status = builder.os.waitpid(child_pid, 0)
            self.assertEqual(builder.os.waitstatus_to_exitcode(status), 23)
            self.assertFalse(bundle.exists())
            builder._write_add_only(outputs, allowed_root=root)
            self.assertTrue(all(path.read_bytes() == content for path, content in outputs.items()))

    def test_add_only_rejects_parent_symlink_and_reserved_review(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "root"
            outside = base / "outside"
            root.mkdir()
            outside.mkdir()
            linked = root / "linked"
            linked.symlink_to(outside, target_is_directory=True)
            redirected = linked / "evidence.json"
            with self.assertRaises(builder.ValidationError):
                builder._write_add_only(
                    {redirected: b"evidence\n"},
                    allowed_root=root,
                )
            self.assertFalse((outside / "evidence.json").exists())

            reserved = root / "independent-review.md"
            reserved.write_text("existing review\n", encoding="utf-8")
            target = root / "bundle" / "ledger.json"
            with self.assertRaises(FileExistsError):
                builder._write_add_only(
                    {target: b"ledger\n"},
                    allowed_root=root,
                    reserved_absent_paths=(reserved,),
                )
            self.assertFalse(target.exists())

    def test_check_rejects_leaf_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            actual = root / "actual.json"
            linked = root / "linked.json"
            actual.write_bytes(b"expected\n")
            linked.symlink_to(actual)
            with self.assertRaises(builder.ValidationError):
                builder._check_committed(
                    {linked: b"expected\n"},
                    allowed_root=root,
                )

    def test_check_is_read_only_and_fails_on_missing_or_stale(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            expected = {
                bundle / "one.json": b"one\n",
                bundle / "two.json": b"two\n",
            }
            builder._write_add_only(expected, allowed_root=root)
            before = {
                path: (path.stat().st_mtime_ns, path.stat().st_size)
                for path in expected
            }
            builder._check_committed(expected, allowed_root=root)
            builder._check_committed(expected, allowed_root=root)
            after = {
                path: (path.stat().st_mtime_ns, path.stat().st_size)
                for path in expected
            }
            self.assertEqual(before, after)
            stale = next(iter(expected))
            stale.write_bytes(b"changed\n")
            with self.assertRaises(builder.ValidationError):
                builder._check_committed(expected, allowed_root=root)
            stale.unlink()
            with self.assertRaises(builder.ValidationError):
                builder._check_committed(expected, allowed_root=root)

    def test_duplicate_json_keys_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "duplicate.json"
            path.write_text('{"a":1,"a":2}\n', encoding="utf-8")
            with self.assertRaises(builder.ValidationError):
                builder.load_strict_json(path)

    @unittest.skipIf(
        builder.R011_PACKET_DIR.exists(),
        "R011 atomic publication directory is already materialized",
    )
    def test_check_cli_rejects_unmaterialized_state_without_writes(self) -> None:
        self.assertFalse(builder.R011_PACKET_DIR.exists())
        with redirect_stderr(io.StringIO()):
            self.assertEqual(builder.main(["--check"]), 1)
        self.assertFalse(builder.R011_PACKET_DIR.exists())

    @unittest.skipUnless(
        all(
            path.exists()
            for path in (
                builder.R011_LEDGER_PATH,
                builder.R011_EVIDENCE_PATH,
                builder.R011_RECEIPT_PATH,
            )
        ),
        "R011 outputs have not been add-only materialized",
    )
    def test_check_cli_verifies_committed_outputs(self) -> None:
        paths = (
            builder.R011_LEDGER_PATH,
            builder.R011_EVIDENCE_PATH,
            builder.R011_RECEIPT_PATH,
        )
        before = {
            path: (path.stat().st_mtime_ns, path.stat().st_size)
            for path in paths
        }
        self.assertEqual(builder.main(["--check"]), 0)
        self.assertEqual(builder.main(["--check"]), 0)
        after = {
            path: (path.stat().st_mtime_ns, path.stat().st_size)
            for path in paths
        }
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
