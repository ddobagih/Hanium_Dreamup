import hashlib
import importlib.util
import inspect
import json
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).parents[1]
SCRIPT = ROOT / "scripts" / "evaluate_android_positioning_offline.py"
SPEC = importlib.util.spec_from_file_location("android_positioning_offline", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

TRACE_FIXTURE = ROOT / "tests" / "fixtures" / "positioning_trace_contract_v2.jsonl"
LEGACY_TRACE_FIXTURE = ROOT / "tests" / "fixtures" / "positioning_trace_contract_v1.jsonl"
TRUTH_FIXTURE = ROOT / "tests" / "fixtures" / "survey_checkpoints_contract_v1.geojson"

ROUTE_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"


def compact_line(value: dict) -> bytes:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, sort_keys=True).encode("utf-8") + b"\n"


def header(session_id: str) -> dict:
    return {
        "schema_version": MODULE.TRACE_SCHEMA,
        "record_type": "header",
        "session_id": session_id,
        "route_id": ROUTE_ID,
        "scenario": "survey",
        "environment": "open_sky",
        "direction": "forward",
        "device_model": "SM-S931N",
        "android_api": 36,
        "mount": MODULE.MOUNT,
        "source_kind": MODULE.TRACE_SOURCE,
        "synthetic_contract_only": False,
        "timebase": MODULE.TRACE_TIMEBASE,
    }


def position(seq: int = 1, time_ns: int = 1_000_000_000) -> dict:
    return {
        "record_type": "position_sample",
        "seq": seq,
        "elapsed_realtime_ns": time_ns,
        "measurement_elapsed_realtime_ns": time_ns,
        "measurement_utc_epoch_ms": 1_700_000_000_000,
        "source": "gnss",
        "raw_position": {"latitude_deg": 37.5, "longitude_deg": 127.0},
        "filtered_position": {"latitude_deg": 37.5, "longitude_deg": 127.0},
        "matched_position": {"latitude_deg": 38.0, "longitude_deg": 128.0},
        "accuracy_m": 1.0,
        "speed_mps": 0.0,
        "stationary": {"stationary": True, "state": "stationary", "zupt_applied": True},
    }


def checkpoint(seq: int = 2, time_ns: int = 1_500_000_000) -> dict:
    return {
        "record_type": "checkpoint_mark",
        "seq": seq,
        "elapsed_realtime_ns": time_ns,
        "measurement_elapsed_realtime_ns": time_ns,
        "measurement_utc_epoch_ms": 1_700_000_000_000,
        "source": "checkpoint_gnss_anchored",
        "checkpoint_id": "cp-1",
        "ordinal": 1,
        "stationary_state": "stationary",
        "stationary_duration_ms": 1500,
    }


def write_session(path: Path, session_id: str, body: list[dict] | None = None, bad_hash: bool = False) -> None:
    prior = [header(session_id), *(body if body is not None else [position(), checkpoint()])]
    content = b"".join(compact_line(item) for item in prior)
    content_hash = "0" * 64 if bad_hash else hashlib.sha256(content).hexdigest()
    footer = {
        "schema_version": MODULE.TRACE_SCHEMA,
        "record_type": "footer",
        "record_count": len(prior) - 1,
        "content_sha256": content_hash,
    }
    path.write_bytes(content + compact_line(footer))


def truth(source_kind: str = MODULE.FIELD_TRUTH_SOURCE, uncertainty_m: float = 0.25) -> dict:
    return {
        "type": "FeatureCollection",
        "schema_version": MODULE.TRUTH_SCHEMA,
        "source_kind": source_kind,
        "coordinate_reference_system": "WGS84",
        "features": [
            {
                "type": "Feature",
                "id": "cp-1",
                "properties": {
                    "checkpoint_id": "cp-1",
                    "route_id": ROUTE_ID,
                    "ordinal": 1,
                    "uncertainty_m": uncertainty_m,
                },
                "geometry": {"type": "Point", "coordinates": [127.0, 37.5]},
            }
        ],
    }


def search() -> dict:
    return {
        "schema_version": MODULE.SEARCH_SCHEMA,
        "expected_device_model": "SM-S931N",
        "expected_mount": MODULE.MOUNT,
        "bounds": {"max_sample_age_ms": [100, 5000], "max_accuracy_m": [0.5, 25.0]},
        "candidates": [{"id": "test", "max_sample_age_ms": 1000, "max_accuracy_m": 5.0}],
        "safety_constraints": {
            "max_checkpoint_uncertainty_m": 0.5,
            "min_train_sessions": 2,
            "min_train_checkpoint_marks": 2,
            "min_train_availability": 0.5,
            "min_holdout_sessions": 2,
            "min_holdout_checkpoint_marks": 2,
        },
        "target_thresholds": {
            "p50_max_m": 2.0,
            "p95_max_m": 5.0,
            "within_2m_coverage_min": 0.5,
            "availability_min": 0.5,
        },
    }


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value), encoding="utf-8")


def materialize(tmp_path: Path, source_kind: str = MODULE.FIELD_TRUTH_SOURCE, uncertainty_m: float = 0.25):
    traces = tmp_path / "traces"
    traces.mkdir()
    for index in range(4):
        session_id = f"00000000-0000-4000-8000-{index:012d}"
        write_session(traces / f"session-{index}.jsonl", session_id)
    truth_path = tmp_path / "truth.geojson"
    search_path = tmp_path / "search.json"
    write_json(truth_path, truth(source_kind, uncertainty_m))
    write_json(search_path, search())
    inventory, loaded_truth = MODULE._load_inputs(traces, truth_path)
    loaded_search = MODULE.load_search_config(search_path)
    split = MODULE.create_split(inventory, loaded_truth, seed="test", holdout_fraction=0.5)
    tuned = MODULE.tune_candidates(
        inventory,
        loaded_truth,
        split,
        loaded_search,
        search_sha256=MODULE._sha256_file(search_path),
    )
    return inventory, loaded_truth, loaded_search, split, tuned, search_path


def attestation(inventory: dict, loaded_truth: dict, loaded_search: dict) -> dict:
    return {
        "schema_version": MODULE.ATTESTATION_SCHEMA,
        "trace_sha256": inventory["sha256"],
        "truth_sha256": loaded_truth["sha256"],
        "surveyed_source": "municipal_control_points_2026",
        "survey_method": "closed_traverse_total_station",
        "maximum_checkpoint_uncertainty_m": max(
            item["uncertainty_m"] for item in loaded_truth["by_id"].values()
        ),
        "expected_device_model": loaded_search["expected_device_model"],
        "expected_mount": loaded_search["expected_mount"],
        "operator_confirmed": True,
    }


def test_replayed_match_evaluation_flag_is_optional_but_strictly_boolean(tmp_path: Path) -> None:
    sample = position()
    sample["source"] = "sensor_gnss_anchored"
    sample["route_match_evaluated"] = True
    sample.pop("raw_position")
    sample.pop("matched_position")
    path = tmp_path / "replayed.jsonl"
    write_session(path, "11111111-1111-4111-8111-111111111111", [sample, checkpoint()])
    MODULE.load_session_trace(path)
    for invalid in (1, "true", None):
        sample["route_match_evaluated"] = invalid
        write_session(path, "11111111-1111-4111-8111-111111111111", [sample, checkpoint()])
        try:
            MODULE.load_session_trace(path)
        except MODULE.ContractError:
            continue
        raise AssertionError("route_match_evaluated must be a Boolean when present")


def test_android_contract_fixture_and_footer_validate() -> None:
    inventory, loaded_truth = MODULE._load_inputs(TRACE_FIXTURE, TRUTH_FIXTURE)
    session = next(iter(inventory["sessions"].values()))

    assert session["session_id"] == "11111111-1111-4111-8111-111111111111"
    assert session["header"]["source_kind"] == "ANDROID_DEBUG_RECORDER"
    assert loaded_truth["source_kind"] == "SYNTHETIC_CONTRACT"


def test_legacy_v1_and_mixed_footer_versions_are_rejected(tmp_path: Path) -> None:
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "schema v1 is unsupported"):
        MODULE.load_session_trace(LEGACY_TRACE_FIXTURE)

    path = tmp_path / "mixed.jsonl"
    write_session(path, "01000000-0000-4000-8000-000000000000")
    lines = path.read_text(encoding="utf-8").splitlines()
    footer = json.loads(lines[-1])
    footer["schema_version"] = MODULE.LEGACY_TRACE_SCHEMA
    path.write_bytes(
        b"".join(line.encode("utf-8") + b"\n" for line in lines[:-1]) + compact_line(footer)
    )
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "mixed trace versions"):
        MODULE.load_session_trace(path)


def test_duplicate_unknown_nonfinite_and_bad_footer_are_rejected(tmp_path: Path) -> None:
    for text in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
        with unittest.TestCase().assertRaises(MODULE.ContractError):
            MODULE._decode_json(text, "test")

    nested_unknown = position()
    nested_unknown["gnss"] = {"unknown": True}
    unknown_path = tmp_path / "unknown.jsonl"
    write_session(unknown_path, "10000000-0000-4000-8000-000000000000", [nested_unknown, checkpoint()])
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "unknown"):
        MODULE.load_session_trace(unknown_path)

    bad_footer = tmp_path / "bad-footer.jsonl"
    write_session(bad_footer, "20000000-0000-4000-8000-000000000000", bad_hash=True)
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "SHA-256"):
        MODULE.load_session_trace(bad_footer)


def test_sequence_and_elapsed_time_are_strict(tmp_path: Path) -> None:
    path = tmp_path / "sequence.jsonl"
    write_session(
        path,
        "30000000-0000-4000-8000-000000000000",
        [position(), checkpoint(seq=1)],
    )
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "seq"):
        MODULE.load_session_trace(path)

    write_session(
        path,
        "30000000-0000-4000-8000-000000000000",
        [position(), checkpoint(time_ns=1_000_000_000)],
    )
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "strictly increasing"):
        MODULE.load_session_trace(path)


def test_measurement_source_and_checkpoint_gate_are_strict(tmp_path: Path) -> None:
    session_id = "31000000-0000-4000-8000-000000000000"
    path = tmp_path / "measurement.jsonl"
    bad_source = position()
    bad_source.pop("measurement_utc_epoch_ms")
    write_session(path, session_id, [bad_source, checkpoint()])
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "disagree"):
        MODULE.load_session_trace(path)

    short_stop = checkpoint()
    short_stop["stationary_duration_ms"] = 1_499
    write_session(path, session_id, [position(), short_stop])
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "1500 ms"):
        MODULE.load_session_trace(path)

    uppercase = checkpoint()
    uppercase["stationary_state"] = "STATIONARY"
    write_session(path, session_id, [position(), uppercase])
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "stationary state"):
        MODULE.load_session_trace(path)

    legacy_name = position()
    legacy_name["record_type"] = "position"
    write_session(path, session_id, [legacy_name, checkpoint()])
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "unsupported record_type"):
        MODULE.load_session_trace(path)


def test_route_checkpoint_id_and_ordinal_must_match_truth(tmp_path: Path) -> None:
    path = tmp_path / "trace.jsonl"
    marked = checkpoint()
    marked["ordinal"] = 2
    write_session(path, "40000000-0000-4000-8000-000000000000", [position(), marked])
    inventory = MODULE.load_trace_inventory(path)
    truth_path = tmp_path / "truth.geojson"
    write_json(truth_path, truth())
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "ordinal"):
        MODULE.validate_inventory_truth(inventory, MODULE.load_truth(truth_path))


def test_causal_join_does_not_use_future_position(tmp_path: Path) -> None:
    path = tmp_path / "trace.jsonl"
    write_session(
        path,
        "50000000-0000-4000-8000-000000000000",
        [checkpoint(seq=1, time_ns=1_000_000_000), position(seq=2, time_ns=2_000_000_000)],
    )
    truth_path = tmp_path / "truth.geojson"
    write_json(truth_path, truth())
    inventory, loaded_truth = MODULE._load_inputs(path, truth_path)
    metrics = MODULE.checkpoint_metrics(
        inventory,
        loaded_truth,
        list(inventory["sessions"]),
        {"id": "test", "max_sample_age_ms": 5000, "max_accuracy_m": 25.0},
        0.5,
    )
    assert metrics["positions_available"] == 0


def test_split_records_hash_inventory_and_recomputes_deterministically(tmp_path: Path) -> None:
    inventory, loaded_truth, _, split, _, _ = materialize(tmp_path)
    second = MODULE.create_split(inventory, loaded_truth, seed="test", holdout_fraction=0.5)
    assert split == second
    assert split["algorithm"] == MODULE.SPLIT_ALGORITHM
    assert len(split["input_session_hashes"]) == 4

    split["train_session_ids"], split["holdout_session_ids"] = (
        split["holdout_session_ids"],
        split["train_session_ids"],
    )
    split_path = tmp_path / "tampered-split.json"
    write_json(split_path, split)
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "recomputation"):
        MODULE.load_split(split_path, inventory, loaded_truth)


def test_source_string_without_attestation_never_reaches_target(tmp_path: Path) -> None:
    inventory, loaded_truth, loaded_search, split, tuned, _ = materialize(tmp_path)
    report = MODULE.evaluate_holdout(
        inventory, loaded_truth, split, loaded_search, tuned, None
    )
    assert report["status"] == "INSUFFICIENT_EVIDENCE"
    assert report["target_eligible"] is False
    assert "FIELD_ATTESTATION_REQUIRED" in report["reason_codes"]


def test_attested_evaluation_uses_filtered_position_not_matched(tmp_path: Path) -> None:
    inventory, loaded_truth, loaded_search, split, tuned, _ = materialize(tmp_path)
    report = MODULE.evaluate_holdout(
        inventory,
        loaded_truth,
        split,
        loaded_search,
        tuned,
        attestation(inventory, loaded_truth, loaded_search),
    )
    assert report["status"] == "TARGET_MET"
    assert report["metric_position_source"] == "FILTERED_UNSNAPPED"
    unittest.TestCase().assertAlmostEqual(report["metrics"]["error_p50_m"], 0.0)


def test_attestation_requires_exact_input_hashes(tmp_path: Path) -> None:
    inventory, loaded_truth, loaded_search, _, _, _ = materialize(tmp_path)
    value = attestation(inventory, loaded_truth, loaded_search)
    value["trace_sha256"] = "0" * 64
    path = tmp_path / "attestation.json"
    write_json(path, value)
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "trace_sha256"):
        MODULE.load_attestation(path, inventory, loaded_truth, loaded_search)


def test_synthetic_truth_cannot_reach_target_even_with_attestation(tmp_path: Path) -> None:
    inventory, loaded_truth, loaded_search, split, tuned, _ = materialize(
        tmp_path, source_kind=MODULE.SYNTHETIC_TRUTH_SOURCE
    )
    report = MODULE.evaluate_holdout(
        inventory,
        loaded_truth,
        split,
        loaded_search,
        tuned,
        attestation(inventory, loaded_truth, loaded_search),
    )
    assert report["status"] == "INSUFFICIENT_EVIDENCE"
    assert "TRUTH_NOT_FIELD_SURVEY" in report["reason_codes"]


def test_checkpoint_uncertainty_over_half_meter_is_ineligible(tmp_path: Path) -> None:
    inventory, loaded_truth, loaded_search, split, tuned, _ = materialize(
        tmp_path, uncertainty_m=0.51
    )
    report = MODULE.evaluate_holdout(
        inventory,
        loaded_truth,
        split,
        loaded_search,
        tuned,
        attestation(inventory, loaded_truth, loaded_search),
    )
    assert report["status"] == "INSUFFICIENT_EVIDENCE"
    assert report["target_eligible"] is False
    assert "CHECKPOINT_UNCERTAINTY_EXCEEDS_0_5_M" in report["reason_codes"]


def test_tuned_candidate_is_recomputed_from_train_inventory(tmp_path: Path) -> None:
    inventory, loaded_truth, loaded_search, split, tuned, search_path = materialize(tmp_path)
    tampered = deepcopy(tuned)
    tampered["selected_candidate"]["max_accuracy_m"] = 25.0
    tuned_path = tmp_path / "tuned.json"
    write_json(tuned_path, tampered)
    with unittest.TestCase().assertRaisesRegex(MODULE.ContractError, "recomputation"):
        MODULE.load_tuned_config(
            tuned_path,
            inventory,
            loaded_truth,
            split,
            loaded_search,
            MODULE._sha256_file(search_path),
        )


def load_tests(loader, tests, pattern):
    """Expose function-style tests to stdlib unittest discovery."""

    suite = unittest.TestSuite()
    functions = [
        value
        for name, value in sorted(globals().items())
        if name.startswith("test_") and inspect.isfunction(value)
    ]
    for function in functions:
        parameters = inspect.signature(function).parameters
        if not parameters:
            suite.addTest(unittest.FunctionTestCase(function, description=function.__name__))
            continue
        if list(parameters) != ["tmp_path"]:
            raise TypeError(f"unsupported unittest adapter signature: {function.__name__}{parameters}")

        def invoke_with_tmp_path(test_function=function):
            with tempfile.TemporaryDirectory() as directory:
                test_function(Path(directory))

        suite.addTest(
            unittest.FunctionTestCase(invoke_with_tmp_path, description=function.__name__)
        )
    return suite
