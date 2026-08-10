from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[1]
SUPPLEMENT_PATH = (
    REPO_ROOT
    / "docs/control/baselines"
    / "walksafe-artifact-baseline-application-temporal-provenance-supplement-20260722-r001.json"
)


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_strict_object)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _object_sha256(value: object) -> str:
    raw = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


class TemporalProvenanceSupplementTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.supplement = _load(SUPPLEMENT_PATH)

    def test_content_hash_and_subject_binding_are_exact(self) -> None:
        body = dict(self.supplement)
        claimed = body.pop("content_sha256")
        self.assertEqual(_object_sha256(body), claimed)

        binding = self.supplement["subject_binding"]
        receipt_path = REPO_ROOT / binding["path"]
        receipt = _load(receipt_path)
        self.assertEqual(_sha256(receipt_path), binding["file_sha256"])
        self.assertEqual(receipt["content_sha256"], binding["content_sha256"])
        self.assertEqual(receipt["metadata"]["receipt_id"], binding["receipt_id"])

    def test_related_evidence_bindings_are_exact(self) -> None:
        for binding in self.supplement["related_evidence_bindings"].values():
            self.assertEqual(_sha256(REPO_ROOT / binding["path"]), binding["file_sha256"])

    def test_time_claim_is_narrowed_without_rewriting_approval(self) -> None:
        finding = self.supplement["finding"]
        boundary = self.supplement["effective_boundary"]
        impact = self.supplement["impact"]
        self.assertEqual(finding["actual_transaction_commit_time"], "NOT_CRYPTOGRAPHICALLY_CAPTURED")
        self.assertEqual(finding["invalid_interpretation"], "EXACT_TRANSACTION_COMMIT_TIME")
        self.assertEqual(boundary["exact_effective_time"], "NOT_CAPTURED")
        self.assertTrue(impact["owner_approval_valid"])
        self.assertTrue(impact["transaction_commit_marker_valid"])
        self.assertTrue(impact["approved_artifact_states_valid"])
        self.assertFalse(impact["existing_immutable_receipt_modified"])
        self.assertFalse(impact["reapproval_required"])

    def test_release_boundary_remains_closed(self) -> None:
        impact = self.supplement["impact"]
        self.assertEqual(impact["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(impact["remaining_gates_are_waived"])


if __name__ == "__main__":
    unittest.main()
