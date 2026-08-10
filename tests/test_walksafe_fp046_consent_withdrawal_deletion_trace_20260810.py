from __future__ import annotations

import ast
from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts import (
    build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810 as builder,
)


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _source_groups(root: Path) -> tuple[builder.SourceGroup, ...]:
    groups = (
        builder.SourceGroup("ANDROID", ("product/android.kt",)),
        builder.SourceGroup("GATEWAY", ("product/gateway.ts",)),
        builder.SourceGroup("BACKEND", ("product/backend.py",)),
        builder.SourceGroup("TOOLING", ("product/trace.py",)),
    )
    for index, group in enumerate(groups, start=1):
        _write(
            root,
            Path(group.paths[0]),
            f"final-content-{index}\n".encode("utf-8"),
        )
    return groups


def _authority() -> dict[str, object]:
    return {
        "goal_binding": {
            "role": "FP046_GOAL",
            "path": builder.GOAL_REL.as_posix(),
            "byte_length": 1,
            "sha256": "a" * 64,
        },
        "start_gate_binding": {
            "role": "FP046_EXACT9_START_GATE",
            "path": builder.START_GATE_REL.as_posix(),
            "byte_length": 1,
            "sha256": "b" * 64,
            "repository_state_path": (
                builder.START_GATE_REPOSITORY_STATE_REL.as_posix()
            ),
            "repository_state_sha256": "c" * 64,
            "event_sequence": builder.EXPECTED_START_EVENT_SEQUENCE,
            "event_id": builder.EXPECTED_START_EVENT_ID,
        },
        "gate_ended_at": "2026-08-09T20:14:48+09:00",
    }


def _lane_raw_outputs() -> dict[str, bytes]:
    outputs: dict[str, bytes] = {}
    for index, lane in enumerate(builder.LANES, start=1):
        passed = lane.expected_passed
        started_at = f"2026-08-10T01:0{index}:00+09:00"
        ended_at = f"2026-08-10T01:0{index}:30+09:00"
        if lane.lane_id == "ANDROID_CONSENT_DELETION":
            runner_summary = (
                f"JUnit tests={passed} failures=0 errors=0 skipped=0"
            )
        elif lane.lane_id == "GATEWAY_PRIVACY_LEDGER":
            runner_summary = (
                f"Node tests={passed} pass={passed} fail=0; "
                "typecheck=PASS; build=PASS"
            )
        else:
            runner_summary = f"{passed} passed in 1.00s"
        outputs[lane.lane_id] = (
            f"WALKSAFE_FP046_COMMAND {lane.expected_command}\n"
            f"WALKSAFE_FP046_STARTED_AT {started_at}\n"
            f"{runner_summary}\n"
            f"WALKSAFE_FP046_SUMMARY passed={passed} failed=0 errors=0 skipped=0\n"
            "WALKSAFE_FP046_EXIT_CODE 0\n"
            f"WALKSAFE_FP046_ENDED_AT {ended_at}\n"
        ).encode("utf-8")
    return outputs


def _lane_observations() -> dict[str, dict[str, object]]:
    raw_outputs = _lane_raw_outputs()
    observations: dict[str, dict[str, object]] = {}
    for index, lane in enumerate(builder.LANES, start=1):
        raw = raw_outputs[lane.lane_id]
        observations[lane.lane_id] = {
            "schema_version": "walksafe.fp046-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": f"2026-08-10T01:0{index}:00+09:00",
            "ended_at": f"2026-08-10T01:0{index}:30+09:00",
            "raw_output_sha256": builder.bytes_sha256(raw),
            "raw_output_byte_length": len(raw),
            "metrics": {
                "result_format": "INTERNAL_TEST_SUMMARY_V1",
                "passed": lane.expected_passed,
                "failed": 0,
                "errors": 0,
                "skipped": 0,
            },
            "evidence_boundary": builder.lane_evidence_boundary(),
        }
    return observations


def _build(root: Path) -> dict[Path, str]:
    return builder.build_pre_review_outputs(
        root=root,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=_source_groups(root),
        authority=_authority(),
    )


def test_trace_is_deterministic_content_bound_and_internal_only(
    tmp_path: Path,
) -> None:
    groups = _source_groups(tmp_path)
    first = builder.build_pre_review_outputs(
        root=tmp_path,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=groups,
        authority=_authority(),
    )
    second = builder.build_pre_review_outputs(
        root=tmp_path,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=groups,
        authority=_authority(),
    )
    assert first == second
    assert tuple(first) == (
        *(lane.log_rel for lane in builder.LANES),
        *(lane.receipt_rel for lane in builder.LANES),
        builder.IMPLEMENTATION_REL,
        builder.VERIFICATION_REL,
        builder.SUCCESSOR_REL,
        builder.REVIEW_SUBJECT_REL,
    )

    implementation = json.loads(first[builder.IMPLEMENTATION_REL])
    verification = json.loads(first[builder.VERIFICATION_REL])
    manifest = implementation["final_content_manifest"]
    builder.validate_implementation_record(
        implementation,
        root=tmp_path,
        expected_groups=groups,
        expected_authority=_authority(),
    )
    builder.validate_verification_result(verification)
    assert manifest["exact_path_count"] == 4
    assert [row["path"] for row in manifest["files"]] == [
        "product/android.kt",
        "product/gateway.ts",
        "product/backend.py",
        "product/trace.py",
    ]
    for row in manifest["files"]:
        raw = (tmp_path / row["path"]).read_bytes()
        assert row["byte_length"] == len(raw)
        assert row["sha256"] == builder.bytes_sha256(raw)
    assert manifest["content_set_sha256"] == builder.object_sha256(
        manifest["files"]
    )
    assert verification["internal_lane_count"] == 4
    assert len(verification["lane_receipts"]) == 4
    assert implementation["completion_boundary"]["formal_test_status"] == "NOT_RUN"
    assert implementation["completion_boundary"]["actual_device_status"] == "NOT_RUN"
    assert implementation["completion_boundary"]["external_privacy_review_status"] == "NOT_RUN"
    assert implementation["completion_boundary"]["production_deployment_status"] == "NOT_RUN"
    assert implementation["completion_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert implementation["completion_boundary"]["release_credit_count"] == 0

    original_path_set = manifest["path_set_sha256"]
    original_content_set = manifest["content_set_sha256"]
    (tmp_path / "product/android.kt").write_bytes(b"changed-final-content\n")
    with pytest.raises(builder.BuildError, match="current file binding differs"):
        builder.validate_implementation_record(
            implementation,
            root=tmp_path,
            expected_groups=groups,
            expected_authority=_authority(),
        )
    changed = builder.build_pre_review_outputs(
        root=tmp_path,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=groups,
        authority=_authority(),
    )
    changed_implementation = json.loads(changed[builder.IMPLEMENTATION_REL])
    changed_manifest = changed_implementation["final_content_manifest"]
    assert changed_manifest["path_set_sha256"] == original_path_set
    assert changed_manifest["content_set_sha256"] != original_content_set
    assert changed[builder.IMPLEMENTATION_REL] != first[builder.IMPLEMENTATION_REL]


def test_resealed_trace_authority_manifest_and_receipt_set_fail_closed(
    tmp_path: Path,
) -> None:
    groups = _source_groups(tmp_path)
    outputs = builder.build_pre_review_outputs(
        root=tmp_path,
        lane_observations=_lane_observations(),
        lane_raw_outputs=_lane_raw_outputs(),
        source_groups=groups,
        authority=_authority(),
    )
    implementation = json.loads(outputs[builder.IMPLEMENTATION_REL])
    verification = json.loads(outputs[builder.VERIFICATION_REL])

    forged_authority = deepcopy(implementation)
    forged_authority["authority_bindings"]["goal"]["sha256"] = "f" * 64
    forged_authority.pop("implementation_record_content_sha256")
    forged_authority = builder.sealed(
        forged_authority,
        "implementation_record_content_sha256",
    )
    with pytest.raises(builder.BuildError, match="authority binding differs"):
        builder.validate_implementation_record(
            forged_authority,
            root=tmp_path,
            expected_groups=groups,
            expected_authority=_authority(),
        )

    forged_manifest = deepcopy(implementation["final_content_manifest"])
    forged_manifest["files"][0], forged_manifest["files"][1] = (
        forged_manifest["files"][1],
        forged_manifest["files"][0],
    )
    forged_manifest["group_order"] = [
        row["group_id"] for row in forged_manifest["files"]
    ]
    forged_manifest["path_set_sha256"] = builder.object_sha256(
        [row["path"] for row in forged_manifest["files"]]
    )
    forged_manifest["content_set_sha256"] = builder.object_sha256(
        forged_manifest["files"]
    )
    forged_manifest.pop("manifest_content_sha256")
    forged_manifest = builder.sealed(
        forged_manifest,
        "manifest_content_sha256",
    )
    with pytest.raises(builder.BuildError, match="canonical group order differs"):
        builder.validate_final_content_manifest(
            forged_manifest,
            root=tmp_path,
            expected_groups=groups,
        )

    forged_verification = deepcopy(verification)
    forged_verification["credit_scope"] = "FORMAL"
    forged_verification.pop("verification_result_content_sha256")
    forged_verification = builder.sealed(
        forged_verification,
        "verification_result_content_sha256",
    )
    with pytest.raises(builder.BuildError, match="verification identity differs"):
        builder.validate_verification_result(forged_verification)

    forged_verification = deepcopy(verification)
    forged_verification["lane_receipts"][0]["lane_id"] = "FAKE"
    forged_verification.pop("verification_result_content_sha256")
    forged_verification = builder.sealed(
        forged_verification,
        "verification_result_content_sha256",
    )
    with pytest.raises(builder.BuildError, match="receipt identity differs"):
        builder.validate_verification_result(forged_verification)

    forged_verification = deepcopy(verification)
    forged_verification["final_content_manifest_sha256"] = "f" * 64
    forged_verification.pop("verification_result_content_sha256")
    forged_verification = builder.sealed(
        forged_verification,
        "verification_result_content_sha256",
    )
    builder.validate_verification_result(forged_verification)
    with pytest.raises(builder.BuildError, match="bundle manifest binding differs"):
        builder.validate_lane_artifacts(
            forged_verification,
            implementation,
            receipt_raw_by_lane={
                lane.lane_id: outputs[lane.receipt_rel].encode("utf-8")
                for lane in builder.LANES
            },
            log_raw_by_lane={
                lane.lane_id: outputs[lane.log_rel].encode("utf-8")
                for lane in builder.LANES
            },
            authority=_authority(),
        )


def test_lane_observation_directory_symlink_is_rejected(tmp_path: Path) -> None:
    real = tmp_path / "real-observations"
    real.mkdir()
    linked = tmp_path / "linked-observations"
    linked.symlink_to(real, target_is_directory=True)
    with pytest.raises(builder.BuildError, match="directory is unsafe"):
        builder.load_lane_observations(linked)


def test_lane_manifest_and_source_tampering_fail_closed(tmp_path: Path) -> None:
    groups = _source_groups(tmp_path)
    observations = _lane_observations()
    lane = builder.LANES[0]
    observations[lane.lane_id]["evidence_boundary"]["formal_test_status"] = "PASS"  # type: ignore[index]
    with pytest.raises(builder.BuildError, match="evidence boundary"):
        builder.build_pre_review_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=_lane_raw_outputs(),
            source_groups=groups,
            authority=_authority(),
        )

    observations = _lane_observations()
    observations[lane.lane_id]["metrics"]["passed"] = 99  # type: ignore[index]
    with pytest.raises(builder.BuildError, match="exact passing-test count"):
        builder.build_pre_review_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=_lane_raw_outputs(),
            source_groups=groups,
            authority=_authority(),
        )

    raw_outputs = _lane_raw_outputs()
    raw_outputs[lane.lane_id] = raw_outputs[lane.lane_id].replace(
        b"WALKSAFE_FP046_EXIT_CODE 0",
        b"WALKSAFE_FP046_EXIT_CODE 1",
    )
    observations = _lane_observations()
    observations[lane.lane_id]["raw_output_sha256"] = builder.bytes_sha256(
        raw_outputs[lane.lane_id]
    )
    observations[lane.lane_id]["raw_output_byte_length"] = len(
        raw_outputs[lane.lane_id]
    )
    with pytest.raises(builder.BuildError, match="raw exit marker"):
        builder.build_pre_review_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=raw_outputs,
            source_groups=groups,
            authority=_authority(),
        )

    forbidden = tmp_path / "apps/web/privacy.ts"
    forbidden.parent.mkdir(parents=True)
    forbidden.write_text("legacy\n")
    with pytest.raises(builder.BuildError, match="forbidden FP-046 source"):
        builder.build_final_content_manifest(
            tmp_path,
            (builder.SourceGroup("FORBIDDEN", ("apps/web/privacy.ts",)),),
        )

    real = tmp_path / "product/real.kt"
    real.write_text("real\n")
    link = tmp_path / "product/link.kt"
    link.symlink_to(real)
    with pytest.raises(builder.BuildError, match="symlink source forbidden"):
        builder.build_final_content_manifest(
            tmp_path,
            (builder.SourceGroup("SYMLINK", ("product/link.kt",)),),
        )

    with pytest.raises(builder.BuildError, match="duplicate JSON member"):
        builder.strict_json_bytes(b'{"status":"PASS","status":"PASS"}', "duplicate")


def test_add_only_writer_never_overwrites_and_only_writes_tmp(
    tmp_path: Path,
) -> None:
    publish_root = tmp_path / "publication"
    publish_root.mkdir()
    outputs = {
        Path("results/a.json"): '{"a":1}\n',
        Path("results/b.json"): '{"b":2}\n',
    }
    builder.write_or_check_outputs(publish_root, outputs, write=True)
    builder.write_or_check_outputs(publish_root, outputs, write=False)
    assert (publish_root / "results/a.json").read_text() == '{"a":1}\n'

    with pytest.raises(builder.BuildError, match="file authority differs"):
        builder.write_or_check_outputs(
            publish_root,
            {Path("results/a.json"): '{"a":999}\n'},
            write=True,
        )
    assert (publish_root / "results/a.json").read_text() == '{"a":1}\n'


def test_canonical_scope_covers_frozen_android_audit_and_real_postgres() -> None:
    expected_post_seq53_drift = {
        "ANDROID_USER_APP_PRIVACY": (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityFirstRunRegistrationStaticTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityFp016StaticTest.kt",
        ),
        "ANDROID_GATEWAY_PRIVACY": (
            "apps/android-gateway/openapi.json",
            "apps/android-gateway/src/auth.ts",
            "apps/android-gateway/src/backend.ts",
            "apps/android-gateway/src/field-long-session.ts",
            "apps/android-gateway/src/routes.ts",
            "apps/android-gateway/test/field-long-session.test.ts",
            "apps/android-gateway/test/gateway-contract.test.ts",
            "apps/android-gateway/test/node-adapter.test.ts",
        ),
        "BACKEND_PRIVACY_LIFECYCLE": (
            "backend/.env.example",
            "backend/app/api/health.py",
            "backend/app/api/reports.py",
            "backend/app/config.py",
            "backend/app/field_test_security.py",
            "backend/app/request_limits.py",
            "backend/app/services/duplicates.py",
            "backend/app/services/actor_rate_limit.py",
            "backend/tests/conftest.py",
            "backend/tests/test_field_test_security.py",
            "backend/tests/test_health_readiness.py",
            "backend/tests/test_openapi_contract.py",
            "backend/tests/test_reports.py",
            "backend/tests/test_reports_v2.py",
        ),
        "RETENTION_BACKUP_AND_TRACE_TOOLING": (
            "docs/backend/api_reference.md",
            "docs/backend/backend_environment.md",
            "scripts/run_walksafe_test_layers_20260711.sh",
        ),
    }
    groups = builder.IMPLEMENTATION_SOURCE_GROUPS
    assert tuple(group.group_id for group in groups) == tuple(
        expected_post_seq53_drift
    )
    assert {group.group_id: len(group.paths) for group in groups} == {
        "ANDROID_USER_APP_PRIVACY": 47,
        "ANDROID_GATEWAY_PRIVACY": 18,
        "BACKEND_PRIVACY_LIFECYCLE": 25,
        "RETENTION_BACKUP_AND_TRACE_TOOLING": 13,
    }
    for group in groups:
        added = expected_post_seq53_drift[group.group_id]
        assert group.paths[-len(added) :] == added
    canonical_paths = [path for group in groups for path in group.paths]
    assert len(canonical_paths) == len(set(canonical_paths)) == 103

    manifest = builder.build_final_content_manifest(builder.ROOT)
    builder.validate_final_content_manifest(
        manifest,
        root=builder.ROOT,
        expected_groups=groups,
    )
    assert manifest["exact_path_count"] == 103
    assert [row["path"] for row in manifest["files"]] == canonical_paths
    for row in manifest["files"]:
        raw = (builder.ROOT / row["path"]).read_bytes()
        assert row["byte_length"] == len(raw)
        assert row["sha256"] == builder.bytes_sha256(raw)

    android = set(builder.ANDROID_PATHS)
    required_tests = {
        "MainActivityAccountDeletionStaticTest.kt",
        "MainActivityWithdrawalRestartStaticTest.kt",
        "AndroidAccountDeletionFallbackMarkerTest.kt",
        "AccountDeletionIntentAuthorityTest.kt",
        "AccountDeletionDualAuthorityTest.kt",
        "AccountDeletionAuthorityRestartTest.kt",
        "PrivacyDeletionHardeningTest.kt",
        "PrivacyAccountDeletionPolicyTest.kt",
        "GatewaySessionProcessCoordinatorTest.kt",
        "GatewayFieldSessionTest.kt",
        "GatewayActivityCallbackPolicyTest.kt",
        "AndroidPrivacyDeletionAccountDeletionTest.kt",
        "AccountDeletionRev0RecoveryPolicyTest.kt",
        "FieldSessionAccountDeletionPrivacyFenceTest.kt",
        "AccountDeletionResetCoordinatorTest.kt",
        "AndroidSensitivePreferenceStoreTest.kt",
    }
    assert required_tests <= {Path(path).name for path in android}
    assert "MainActivityNavigationCompositionTest.kt" in {
        Path(path).name for path in android
    }
    android_lane = builder.LANE_BY_ID["ANDROID_CONSENT_DELETION"]
    assert android_lane.expected_command == (
        "cd apps/android && ./gradlew :app:testDebugUnitTest --offline "
        "--no-daemon --rerun-tasks"
    )
    assert android_lane.expected_passed == 981
    backend_lane = builder.LANE_BY_ID["BACKEND_PRIVACY_POSTGRES"]
    assert backend_lane.expected_passed == 57
    assert "WALKSAFE_TEST_DATABASE_URL='postgresql+psycopg://" in (
        backend_lane.expected_command
    )
    assert "PYTHONPATH=." in backend_lane.expected_command
    assert "PYTHONPATH=backend" not in backend_lane.expected_command
    retention_lane = builder.LANE_BY_ID["RETENTION_BACKUP_DELETION"]
    assert retention_lane.expected_passed == 92
    assert retention_lane.expected_command.startswith(
        ".venv/bin/python -m pytest -p no:cacheprovider -q "
    )
    assert builder.LOCKED_TEST_PYTHON not in retention_lane.expected_command
    assert builder.LANE_BY_ID["GATEWAY_PRIVACY_LEDGER"].expected_passed == 88


def test_backend_import_closure_is_exact_pinned_and_fails_on_byte_drift(
    tmp_path: Path,
) -> None:
    drift_paths = builder.BACKEND_MANIFEST_DRIFT_PATHS
    transitive_paths = tuple(
        path.as_posix()
        for path in builder.BACKEND_TRANSITIVE_INPUT_SHA256_BY_PATH
    )
    closure_paths = builder.BACKEND_EXPLICIT_IMPORT_CLOSURE_PATHS
    assert drift_paths == (
        "backend/app/services/duplicates.py",
        "backend/app/services/actor_rate_limit.py",
    )
    assert len(transitive_paths) == len(set(transitive_paths)) == 28
    assert len(closure_paths) == len(set(closure_paths)) == 30
    assert not (set(drift_paths) & set(transitive_paths))
    assert set(drift_paths) | set(transitive_paths) == set(closure_paths)
    assert tuple(path for path in closure_paths if path in set(drift_paths)) == (
        drift_paths
    )
    assert tuple(
        path for path in closure_paths if path in set(transitive_paths)
    ) == transitive_paths

    live_bindings = builder.validate_backend_explicit_import_closure(
        builder.ROOT
    )
    assert len(live_bindings["manifest_drift"]) == 2
    assert len(live_bindings["transitive_inputs"]) == 28
    assert tuple(
        path.as_posix()
        for path in builder.BACKEND_MANIFEST_DRIFT_SEQ53_SHA256_BY_PATH
    ) == drift_paths
    for relative, seq53_sha256 in (
        builder.BACKEND_MANIFEST_DRIFT_SEQ53_SHA256_BY_PATH.items()
    ):
        current = (builder.ROOT / relative).read_bytes()
        assert builder.bytes_sha256(current) != seq53_sha256

    imported_modules_by_source = {}
    for relative in (
        Path("backend/app/api/reports.py"),
        Path("backend/app/field_test_security.py"),
    ):
        tree = ast.parse((builder.ROOT / relative).read_text(encoding="utf-8"))
        imported_modules_by_source[relative] = {
            node.module
            for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module is not None
        }
    assert "backend.app.services.duplicates" in imported_modules_by_source[
        Path("backend/app/api/reports.py")
    ]
    assert "backend.app.services.actor_rate_limit" in (
        imported_modules_by_source[Path("backend/app/field_test_security.py")]
    )

    for relative_text in (*drift_paths, *transitive_paths):
        relative = Path(relative_text)
        _write(tmp_path, relative, (builder.ROOT / relative).read_bytes())
    builder.validate_backend_explicit_import_closure(tmp_path)
    drifted = Path(transitive_paths[0])
    (tmp_path / drifted).write_bytes(
        (tmp_path / drifted).read_bytes() + b"\n# byte drift\n"
    )
    with pytest.raises(
        builder.BuildError,
        match="backend transitive source SHA-256 differs",
    ):
        builder.validate_backend_explicit_import_closure(tmp_path)
