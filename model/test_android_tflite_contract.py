"""Regression checks for the active class map, artifact and raw tensor contract."""

import argparse
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import check_android_tflite_contract_20260531 as contract


class AndroidContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = json.loads(contract.ANDROID_CONFIG.read_text(encoding="utf-8"))

    def check_config(self, config, assets=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            args = argparse.Namespace(android_config=path, backend_config=None, strict_backend_config=False)
            if assets is None:
                return contract.run_check(args)
            with patch.object(contract, "check_asset", side_effect=lambda asset: assets[asset]):
                return contract.run_check(args)

    def test_active_model_uses_21_class_index_contract_and_hashes(self):
        result = self.check_config(self.config)
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(21, len(self.config["models"]["unified_walksafe"]["classes"]))
        self.assertFalse(result.expected_fallback)
        self.assertEqual("unified_walksafe", result.expected_runtime_model)

    def test_class_order_change_is_rejected(self):
        changed = copy.deepcopy(self.config)
        classes = changed["models"]["unified_walksafe"]["classes"]
        classes[0], classes[1] = classes[1], classes[0]
        result = self.check_config(changed)
        self.assertFalse(result.ok)
        self.assertTrue(any("classes mismatch" in e for e in result.errors))

    def test_wrong_artifact_hash_is_rejected(self):
        changed = copy.deepcopy(self.config)
        changed["models"]["unified_walksafe"]["artifact_sha256"] = "0" * 64
        self.assertTrue(any("SHA-256 mismatch" in e for e in self.check_config(changed).errors))

    def test_missing_primary_without_fallback_does_not_claim_legacy_runtime(self):
        assets = {m["asset"]: contract.check_asset(m["asset"]) for m in self.config["models"].values()}
        assets[self.config["models"]["unified_walksafe"]["asset"]]["exists"] = False
        result = self.check_config(self.config, assets)
        self.assertFalse(result.ok)
        self.assertFalse(result.expected_fallback)
        self.assertIsNone(result.expected_runtime_model)

    def test_inactive_legacy_assets_are_not_required(self):
        assets = {m["asset"]: contract.check_asset(m["asset"]) for m in self.config["models"].values()}
        for key in ("custom_tactile", "coco_general"):
            assets[self.config["models"][key]["asset"]]["exists"] = False
        result = self.check_config(self.config, assets)
        self.assertTrue(result.ok, result.errors)
        self.assertEqual(2, len(result.warnings))

    def test_raw_channels_must_equal_four_plus_class_count(self):
        model = copy.deepcopy(self.config["models"]["unified_walksafe"])
        model["export"]["output_shape"] = [1, 26, 12096]
        errors = []
        contract.assert_raw_tensor_contract(errors, model)
        self.assertTrue(any("4 + class_count" in e for e in errors))

    def test_nms_output_cannot_masquerade_as_raw_output(self):
        model = copy.deepcopy(self.config["models"]["unified_walksafe"])
        model["export"]["output_shape"] = [1, 300, 6]
        model["export"]["nms_requested"] = True
        errors = []
        contract.assert_raw_tensor_contract(errors, model)
        self.assertEqual(2, len(errors))

    def test_boolean_threshold_is_not_a_probability(self):
        errors = []
        contract.assert_thresholds_present(errors, "model", {"thresholds": {"person": True}}, ["person"])
        self.assertEqual(1, len(errors))

    def test_max_detections_matches_android_upper_bound(self):
        for value in (0, 301, True, 1.5):
            with self.subTest(value=value):
                model = copy.deepcopy(self.config["models"]["unified_walksafe"])
                model["max_detections"] = value
                errors = []
                contract.assert_raw_tensor_contract(errors, model)
                self.assertTrue(any("between 1 and 300" in e for e in errors))

    def test_asset_path_escape_is_not_read(self):
        result = contract.check_asset("../model-config/two_model_runtime.json")
        self.assertFalse(result["safe_path"])
        self.assertFalse(result["exists"])
        self.assertIsNone(result["sha256"])

    def test_host_smoke_validates_current_raw_output_values(self):
        import numpy as np
        from scripts.smoke_android_tflite_runtime_20260711 import validate_output

        model = self.config["models"]["unified_walksafe"]
        output = np.zeros(model["export"]["output_shape"], dtype=np.float32)
        # Raw box centers may lie outside the image; don't impose a made-up clipping rule.
        output[0, 0, 0] = -0.1
        output[0, 1, 0] = 1.1
        self.assertEqual(21, validate_output(output, model)["class_count"])
        for channel, value in [(2, -0.1), (3, -0.1), (4, -0.1), (4, 1.1), (0, np.nan), (4, np.inf)]:
            with self.subTest(channel=channel, value=value):
                invalid = output.copy()
                invalid[0, channel, 0] = value
                with self.assertRaises(ValueError):
                    validate_output(invalid, model)
        for invalid in (output.astype(np.float64), output[:, :-1, :]):
            with self.assertRaises(ValueError):
                validate_output(invalid, model)

    def test_threshold_allowlist_rejects_undeclared_changed_and_stale_differences(self):
        declared, errors = contract.load_threshold_allowlist(contract.THRESHOLD_ALLOWLIST)
        self.assertFalse(errors)
        differences = [{k: v for k, v in entry.items() if k != "reason"} for entry in declared.values()]
        self.assertEqual(([], []), contract.evaluate_threshold_allowlist(differences, declared))
        changed = copy.deepcopy(differences)
        changed[0]["android_threshold"] += 0.01
        unapproved, stale = contract.evaluate_threshold_allowlist(changed, declared)
        self.assertEqual(1, len(unapproved))
        self.assertEqual(1, len(stale))
        self.assertEqual(len(differences), len(contract.evaluate_threshold_allowlist(differences, {})[0]))
        self.assertEqual(1, len(contract.evaluate_threshold_allowlist(differences[1:], declared)[1]))


if __name__ == "__main__":
    unittest.main()
