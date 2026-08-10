"""Regressions for the authorized WalkSafe v2.4 seq39 projection."""

from __future__ import annotations

import copy
from pathlib import Path
import unittest

from scripts import check_walksafe_project_continuation_v2_4 as contract


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeGoalGraphV24Seq39Test(unittest.TestCase):
    def test_historical_authorization_is_deterministic(self) -> None:
        authorization = contract.load_json(
            ROOT / contract.V24_SEQ39_AUTHORIZATION_RELATIVE
        )
        self.assertEqual(
            authorization,
            contract.expected_seq39_authorization_receipt(),
        )
        self.assertEqual(
            contract.validate_seq39_canonical_binding_update(ROOT),
            [],
        )

    def test_projection_rebuilds_from_the_sealed_history_prefix(self) -> None:
        checkpoint_path = ROOT / contract.V24_CHECKPOINT_RELATIVE
        checkpoint = contract.load_json(checkpoint_path)
        authorization_sha256 = contract.sha256_file(
            ROOT / contract.V24_SEQ39_AUTHORIZATION_RELATIVE
        )

        self.assertEqual(
            checkpoint["goal_execution"]["transition_history"][38],
            contract.expected_seq39_event_from_history_prefix(
                checkpoint,
                authorization_sha256,
            ),
        )

    def test_projection_rejects_historical_event_tampering(self) -> None:
        checkpoint = contract.load_json(
            ROOT / contract.V24_CHECKPOINT_RELATIVE
        )
        checkpoint = copy.deepcopy(checkpoint)
        checkpoint["goal_execution"]["transition_history"][38][
            "authorization_scope_sha256"
        ] = "0" * 64

        self.assertIn(
            "v2.4 seq39 event differs",
            contract.validate_seq39_canonical_binding_update(
                ROOT,
                checkpoint,
            ),
        )


if __name__ == "__main__":
    unittest.main()
