from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
LEGACY_SUBMISSION_RUNBOOK = ROOT / "docs/submission/CLEAN_ROOM_REPRODUCTION_20260713.md"
LEGACY_SUBMISSION_FACTS = ROOT / "docs/submission/form_materials/09_제출_사실_기준.json"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import submission_manifest_policy as manifest_policy  # noqa: E402
from submission_manifest_policy import (  # noqa: E402
    ALL_SUBMISSION_GENERATED_PATHS,
    ASSET_ARTIFACT_PATHS,
    ASSET_DIRECTORY_FILES,
    ASSET_REPOSITORY_INPUT_PATHS,
    DESIGN_BUILD_INPUT_PATHS,
    FINAL_SECTION_PATHS,
    FORM_ASSET_PATHS,
    FORM_BUILD_INPUT_PATHS,
    SUBMISSION_PYTHON_TRUST_SOURCES,
    file_record,
    record_bundle_sha256,
    require_exact_file_set,
    require_exact_manifest_paths,
    require_clean_source_revision,
    run_attested_submission_tool,
    sha256,
    source_revision,
    submission_toolchain_attestation,
)
from validate_submission_materials_20260710 import validate_core_semantic_facts  # noqa: E402


def test_submission_generated_paths_and_final_build_sources_are_complete() -> None:
    assert len(ALL_SUBMISSION_GENERATED_PATHS) == 36
    semantic_sidecar = "docs/submission/form_materials/assets/evidence_results.semantic.json"
    assert semantic_sidecar in ALL_SUBMISSION_GENERATED_PATHS
    assert semantic_sidecar in ASSET_ARTIFACT_PATHS
    assert semantic_sidecar in FORM_ASSET_PATHS
    assert "evidence_results.semantic.json" in ASSET_DIRECTORY_FILES
    assert {
        "configs/submission_toolchain_lock_20260713.json",
        "docs/submission/CLEAN_ROOM_REPRODUCTION_20260713.md",
        "scripts/audit_submission_visual_privacy_20260711.py",
        "scripts/promote_submission_final_20260713.py",
        "scripts/run_walksafe_isolated_python_20260713.py",
        "scripts/run_walksafe_submission_python_20260714.py",
    } <= FINAL_SECTION_PATHS["build_sources"]
    assert SUBMISSION_PYTHON_TRUST_SOURCES <= DESIGN_BUILD_INPUT_PATHS
    assert SUBMISSION_PYTHON_TRUST_SOURCES <= ASSET_REPOSITORY_INPUT_PATHS
    assert SUBMISSION_PYTHON_TRUST_SOURCES <= FORM_BUILD_INPUT_PATHS
    assert {
        "configs/submission_installer_requirements.lock",
        "configs/submission_exact8_requirements.lock",
    } <= SUBMISSION_PYTHON_TRUST_SOURCES


@pytest.mark.skipif(
    not LEGACY_SUBMISSION_RUNBOOK.is_file(),
    reason="legacy local-only submission runbook is not present in the current repository",
)
def test_clean_room_contract_keeps_generated_rc_outside_source_branch() -> None:
    runbook = LEGACY_SUBMISSION_RUNBOOK.read_text(encoding="utf-8")

    assert "source branch에 다시 커밋하지 않는다" in runbook
    assert 'artifact_root="/secure/artifacts/walksafe-submission-${SOURCE_COMMIT}"' in runbook
    assert "safe_git clone --no-local <repository-path> validation-clone" in runbook
    assert '--submission-asset-dir "${artifact_root}/assets"' in runbook
    assert '--design-document-dir "${artifact_root}/design-documents"' in runbook
    assert '--final-submission-dir "${artifact_root}/final"' in runbook
    assert 'cp clone-a/templates/SUBMISSION_BUILD_MANIFEST.json "${artifact_root}/SUBMISSION_BUILD_MANIFEST.json"' in runbook
    assert '--submission-form-manifest "${artifact_root}/SUBMISSION_BUILD_MANIFEST.json"' in runbook
    assert '--submission-privacy-receipt "${artifact_root}/final/ASSISTANT_VISUAL_PRIVACY_REVIEW.json"' in runbook
    assert "configs/submission_installer_requirements.lock" in runbook
    assert "configs/submission_exact8_requirements.lock" in runbook
    assert "--require-hashes --no-deps --no-compile" in runbook
    assert '"26.1.1"' in runbook
    assert '-m pip uninstall -y pip' in runbook
    assert 'chmod -R go-rwx "${SUBMISSION_VENV}"' in runbook


@pytest.mark.skipif(
    not LEGACY_SUBMISSION_FACTS.is_file(),
    reason="legacy local-only submission facts are not present in the current repository",
)
def test_canonical_release_gates_bind_exact_model_and_product_blockers() -> None:
    facts = json.loads(
        LEGACY_SUBMISSION_FACTS.read_text(encoding="utf-8")
    )
    expected_model_blockers = [
        "independent_test_missing",
        "dataset_content_hashes_missing",
        "capture_sequence_split_leakage",
        "curb_step_recall_below_field_gate",
        "uneven_sidewalk_recall_below_field_gate",
    ]
    assert facts["model_data"]["blockers"] == expected_model_blockers
    assert facts["release_gates"][0]["open_items"] == expected_model_blockers
    assert [gate["id"] for gate in facts["release_gates"]] == [
        "MODEL_DEPLOYMENT_QUALITY",
        "MOBILE_FIELD_AND_ACCESSIBILITY",
        "TACTILE_PLATFORM_FIELD_AND_SCOPE",
        "PRODUCTION_OPERATIONS",
        "INSTITUTION_SUBMISSION",
    ]
    assert facts["release_gates"][2]["open_items"] == [
        "web_camera_route_projection_supplier_missing",
        "web_camera_non_metric_real_phone_unverified",
        "android_camera_calibration_eis_unverified",
        "android_gps_drift_unverified",
        "android_tactile_local_steering_field_unverified",
        "android_arcore_unsupported_camera_non_metric_field_unverified",
    ]
    assert facts["recorded_product_decisions"] == {
        "recorded_at": "2026-07-16",
        "web": "CAMERA_NON_METRIC_ADVISORY_WITH_TMAP_ROUTE_AUTHORITY",
        "android_arcore_unsupported": "INSTALLABLE_CAMERA_IMU_NON_METRIC_LIMITED_MODE",
        "equal_severity": {
            "option": "3A",
            "policy": "BOUNDED_SEQUENTIAL_HANDOFF_WITH_DELIVERY_RECHECK",
        },
        "source_freeze": {
            "option": "4A",
            "status": "LOCAL_COMMIT_AUTHORIZED_NO_PUSH",
            "push_authorized": False,
        },
    }
    errors: list[str] = []
    validate_core_semantic_facts(facts, errors)
    assert errors == []


@pytest.mark.skipif(
    not LEGACY_SUBMISSION_FACTS.is_file(),
    reason="legacy local-only submission facts are not present in the current repository",
)
def test_canonical_non_metric_advisory_is_bounded_and_report_isolated() -> None:
    facts = json.loads(
        LEGACY_SUBMISSION_FACTS.read_text(encoding="utf-8")
    )
    advisory = facts["runtime"]["navigation"]["camera_non_metric_advisory"]
    assert advisory["platform_tiers"] == {
        "web": "CAMERA_NON_METRIC_ADVISORY",
        "android_arcore_supported": "ARCORE_METRIC",
        "android_arcore_unsupported": "CAMERA_IMU_NON_METRIC",
        "fallback": "TMAP_ONLY",
    }
    assert advisory["stability"] == {
        "min_distinct_consecutive_frames": 3,
        "min_stable_ms": 700,
    }
    assert advisory["report_policy"] == {
        "degraded_advisory_reports": False,
        "android_limited_mode_reports": False,
        "web_existing_explicit_consent_server_v2_damaged_report_pipeline": "unchanged",
    }

    unsafe_risk = copy.deepcopy(facts)
    unsafe_risk["runtime"]["navigation"]["camera_non_metric_advisory"]["risk_level"] = "high"
    errors: list[str] = []
    validate_core_semantic_facts(unsafe_risk, errors)
    assert "canonical non-metric advisory shape/status/directions drifted" in errors

    report_enabled = copy.deepcopy(facts)
    report_enabled["runtime"]["navigation"]["camera_non_metric_advisory"]["report_policy"][
        "android_limited_mode_reports"
    ] = True
    errors = []
    validate_core_semantic_facts(report_enabled, errors)
    assert "canonical non-metric report isolation or Field evidence boundary drifted" in errors

    weakened_stability = copy.deepcopy(facts)
    weakened_stability["runtime"]["navigation"]["camera_non_metric_advisory"]["stability"][
        "min_distinct_consecutive_frames"
    ] = 2
    errors = []
    validate_core_semantic_facts(weakened_stability, errors)
    assert "canonical non-metric three-distinct-frame/700ms stability drifted" in errors

    web_imu_required = copy.deepcopy(facts)
    web_imu_required["runtime"]["navigation"]["camera_non_metric_advisory"][
        "platform_gates"
    ]["web"] = (
        "후면 camera·detector·활성 TMAP 안내·foreground session·page visibility·"
        "fresh detection·IMU가 모두 필수다. 실패 시 TMAP_ONLY다."
    )

    android_without_fresh_imu = copy.deepcopy(facts)
    android_without_fresh_imu["runtime"]["navigation"]["camera_non_metric_advisory"][
        "platform_gates"
    ]["android_arcore_unsupported"] = (
        "camera permission·CameraX fallback·detector·활성 TMAP route가 필수다. "
        "하나라도 실패하면 TMAP_ONLY다."
    )

    replaced_existing_risk = copy.deepcopy(facts)
    replaced_existing_risk["runtime"]["navigation"]["camera_non_metric_advisory"][
        "selection_policy"
    ] = "비계량 advisory가 기존 Web risk를 대체한다."

    changed_damaged_report = copy.deepcopy(facts)
    changed_damaged_report["runtime"]["navigation"]["camera_non_metric_advisory"][
        "report_policy"
    ]["web_existing_explicit_consent_server_v2_damaged_report_pipeline"] = "disabled"

    falsely_verified_field = copy.deepcopy(facts)
    falsely_verified_field["runtime"]["navigation"]["camera_non_metric_advisory"][
        "field_evidence"
    ]["web_real_phone"] = "VERIFIED"

    changed_three_a = copy.deepcopy(facts)
    changed_three_a["recorded_product_decisions"]["equal_severity"]["option"] = "3B"

    unapproved_push = copy.deepcopy(facts)
    unapproved_push["recorded_product_decisions"]["source_freeze"]["push_authorized"] = True

    for mutated, expected_error in (
        (web_imu_required, "canonical platform-specific non-metric fallback gates drifted"),
        (android_without_fresh_imu, "canonical platform-specific non-metric fallback gates drifted"),
        (replaced_existing_risk, "canonical non-metric Web selection isolation drifted"),
        (changed_damaged_report, "canonical non-metric report isolation or Field evidence boundary drifted"),
        (falsely_verified_field, "canonical non-metric report isolation or Field evidence boundary drifted"),
        (changed_three_a, "canonical recorded product decisions drifted"),
        (unapproved_push, "canonical recorded product decisions drifted"),
    ):
        errors = []
        validate_core_semantic_facts(mutated, errors)
        assert expected_error in errors


def test_exact_output_set_rejects_missing_and_unexpected_files(tmp_path: Path) -> None:
    (tmp_path / "a.docx").write_bytes(b"a")

    with pytest.raises(ValueError, match=r"missing=.*b\.docx"):
        require_exact_file_set(tmp_path, {"a.docx", "b.docx"})

    (tmp_path / "b.docx").write_bytes(b"b")
    (tmp_path / "stale.pdf").write_bytes(b"stale")
    with pytest.raises(ValueError, match=r"unexpected=.*stale\.pdf"):
        require_exact_file_set(tmp_path, {"a.docx", "b.docx"})


def test_exact_output_set_rejects_symlink(tmp_path: Path) -> None:
    source = tmp_path / "source.docx"
    source.write_bytes(b"source")
    (tmp_path / "alias.docx").symlink_to(source)

    with pytest.raises(ValueError, match="symlinks"):
        require_exact_file_set(tmp_path, {"source.docx", "alias.docx"})


def test_exact_output_set_rejects_nested_directory(tmp_path: Path) -> None:
    (tmp_path / "artifact.docx").write_bytes(b"artifact")
    (tmp_path / "stale-render").mkdir()

    with pytest.raises(ValueError, match="non-files"):
        require_exact_file_set(tmp_path, {"artifact.docx"})


def test_exact_manifest_paths_rejects_duplicate_and_path_substitution() -> None:
    expected = {"one.md", "two.md"}
    duplicate = [{"path": "one.md"}, {"path": "one.md"}]
    substituted = [{"path": "one.md"}, {"path": "other.md"}]

    with pytest.raises(ValueError, match="path set mismatch"):
        require_exact_manifest_paths("sources", duplicate, expected)
    with pytest.raises(ValueError, match="path set mismatch"):
        require_exact_manifest_paths("sources", substituted, expected)


def test_file_records_and_bundle_hash_bind_path_and_bytes(tmp_path: Path) -> None:
    first = tmp_path / "a.txt"
    second = tmp_path / "b.txt"
    first.write_text("alpha", encoding="utf-8")
    second.write_text("beta", encoding="utf-8")

    records = [file_record(tmp_path, first), file_record(tmp_path, second)]
    original = record_bundle_sha256(records)
    assert original == record_bundle_sha256(list(reversed(records)))

    first.write_text("changed", encoding="utf-8")
    changed = [file_record(tmp_path, first), file_record(tmp_path, second)]
    assert record_bundle_sha256(changed) != original

    with pytest.raises(ValueError, match="duplicate manifest bundle path"):
        record_bundle_sha256([records[0], records[0]])


def test_file_record_rejects_repository_escape(tmp_path: Path) -> None:
    outside = tmp_path.parent / "outside-submission-manifest.txt"
    outside.write_text("outside", encoding="utf-8")
    try:
        with pytest.raises(ValueError, match="outside repository"):
            file_record(tmp_path, outside)
    finally:
        outside.unlink()


def test_source_revision_excludes_only_declared_generated_outputs(tmp_path: Path) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"],
        check=True,
    )
    source = tmp_path / "source.md"
    output = tmp_path / "generated.docx"
    source.write_text("source", encoding="utf-8")
    output.write_text("generated", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)

    clean = source_revision(tmp_path, {"generated.docx"})
    assert clean["source_dirty"] is False
    assert clean["source_dirty_excluded_generated_paths"] == ["generated.docx"]

    output.write_text("rebuilt", encoding="utf-8")
    assert source_revision(tmp_path, {"generated.docx"})["source_dirty"] is False
    assert source_revision(tmp_path)["source_dirty"] is True

    receipt = tmp_path / "build-receipt.json"
    receipt.write_text("{}", encoding="utf-8")
    assert source_revision(
        tmp_path, {"generated.docx", "build-receipt.json"}
    )["source_dirty"] is False

    source.write_text("changed source", encoding="utf-8")
    assert source_revision(
        tmp_path, {"generated.docx", "build-receipt.json"}
    )["source_dirty"] is True


def test_source_revision_does_not_hide_other_untracked_files(tmp_path: Path) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"],
        check=True,
    )
    tracked = tmp_path / "tracked.md"
    tracked.write_text("tracked", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)

    (tmp_path / "untracked.txt").write_text("untracked", encoding="utf-8")
    assert source_revision(tmp_path, {"generated.docx"})["source_dirty"] is True

    with pytest.raises(ValueError, match="normalized relative path"):
        source_revision(tmp_path, {"."})


def test_source_revision_uses_verified_runner_marker_without_git(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commit = "c" * 40
    generated = tuple(sorted(ALL_SUBMISSION_GENERATED_PATHS))
    main_module = sys.modules["__main__"]
    monkeypatch.setattr(main_module, "_walksafe_submission_source_commit", commit, raising=False)
    monkeypatch.setattr(main_module, "_walksafe_submission_repo_root", str(tmp_path), raising=False)
    monkeypatch.setattr(
        main_module,
        "_walksafe_submission_generated_paths",
        generated,
        raising=False,
    )
    monkeypatch.setenv("WALKSAFE_SUBMISSION_SOURCE_COMMIT", commit)

    def reject_git(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("verified runner path must not invoke Git")

    monkeypatch.setattr(subprocess, "run", reject_git)
    revision = source_revision(tmp_path, generated)

    assert revision == {
        "source_commit": commit,
        "source_dirty": False,
        "source_dirty_excluded_generated_paths": list(generated),
    }


@pytest.mark.parametrize("case", ["missing", "partial", "commit", "root", "generated"])
def test_source_revision_rejects_invalid_runner_marker(
    case: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    commit = "d" * 40
    generated = tuple(sorted(ALL_SUBMISSION_GENERATED_PATHS))
    main_module = sys.modules["__main__"]
    monkeypatch.setenv("WALKSAFE_SUBMISSION_SOURCE_COMMIT", commit)
    for name in (
        "_walksafe_submission_source_commit",
        "_walksafe_submission_repo_root",
        "_walksafe_submission_generated_paths",
    ):
        monkeypatch.delattr(main_module, name, raising=False)
    if case != "missing":
        monkeypatch.setattr(
            main_module,
            "_walksafe_submission_source_commit",
            "e" * 40 if case == "commit" else commit,
            raising=False,
        )
    if case not in {"missing", "partial"}:
        monkeypatch.setattr(
            main_module,
            "_walksafe_submission_repo_root",
            str(tmp_path / "wrong") if case == "root" else str(tmp_path),
            raising=False,
        )
        monkeypatch.setattr(
            main_module,
            "_walksafe_submission_generated_paths",
            ("wrong",) if case == "generated" else generated,
            raising=False,
        )

    def reject_git(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("invalid runner marker must fail before Git")

    monkeypatch.setattr(subprocess, "run", reject_git)
    with pytest.raises(ValueError, match="runner source verification marker"):
        source_revision(tmp_path, generated)


def test_clean_source_revision_fails_closed_but_excludes_generated_directory(
    tmp_path: Path,
) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "config", "user.name", "Test"], check=True)
    subprocess.run(
        ["git", "-C", str(tmp_path), "config", "user.email", "test@example.invalid"],
        check=True,
    )
    source = tmp_path / "source.md"
    source.write_text("source", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp_path), "add", "."], check=True)
    subprocess.run(["git", "-C", str(tmp_path), "commit", "-qm", "fixture"], check=True)

    generated = tmp_path / "generated"
    generated.mkdir()
    (generated / "artifact.json").write_text("{}", encoding="utf-8")
    assert require_clean_source_revision(tmp_path, {"generated"})["source_dirty"] is False

    source.write_text("dirty", encoding="utf-8")
    with pytest.raises(ValueError, match="submission source must be clean"):
        require_clean_source_revision(tmp_path, {"generated"})


def test_submission_toolchain_lock_rejects_wrong_python_and_duplicate_keys(
    tmp_path: Path,
) -> None:
    root = Path(__file__).resolve().parents[1]
    lock_path = root / "configs/submission_toolchain_lock_20260713.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    lock["python"]["executable"]["sha256"] = "0" * 64
    altered = tmp_path / "altered-toolchain-lock.json"
    altered.write_text(json.dumps(lock), encoding="utf-8")
    with pytest.raises(ValueError, match="submission Python differs from lock"):
        submission_toolchain_attestation(root, altered)

    duplicate = tmp_path / "duplicate-toolchain-lock.json"
    duplicate.write_text(
        '{"schema_version":"walksafe.submission-toolchain.v4",'
        '"schema_version":"walksafe.submission-toolchain.v4"}',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="duplicate submission toolchain lock key"):
        submission_toolchain_attestation(root, duplicate)


def test_submission_python_installation_lock_binds_both_requirements_files(
    tmp_path: Path,
) -> None:
    root = Path(__file__).resolve().parents[1]
    lock = json.loads(
        (root / "configs/submission_toolchain_lock_20260713.json").read_text(encoding="utf-8")
    )
    installation = lock["python_installation"]
    for record in (installation["installer_lock"], installation["exact8_lock"]):
        source = root / record["path"]
        destination = tmp_path / record["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(source.read_bytes())

    manifest_policy._require_python_installation_lock(tmp_path, installation)

    (tmp_path / installation["exact8_lock"]["path"]).write_bytes(b"tampered\n")
    with pytest.raises(ValueError, match="differs from toolchain lock"):
        manifest_policy._require_python_installation_lock(tmp_path, installation)


def test_submission_toolchain_lock_binds_actual_libreoffice_runtime_chain() -> None:
    root = Path(__file__).resolve().parents[1]
    lock = json.loads(
        (root / "configs/submission_toolchain_lock_20260713.json").read_text(encoding="utf-8")
    )
    libreoffice = next(tool for tool in lock["tools"] if tool["name"] == "libreoffice")
    launcher = Path(libreoffice["resolved_path"])
    expected_paths = (
        launcher.with_name("oosplash"),
        launcher.with_name("soffice.bin"),
    )

    assert manifest_policy._runtime_chain_paths("libreoffice", launcher) == expected_paths
    assert tuple(
        Path(record["resolved_path"]) for record in libreoffice["runtime_chain"]
    ) == expected_paths
    for record, path in zip(libreoffice["runtime_chain"], expected_paths, strict=True):
        assert record == _file_lock(path)


def test_submission_toolchain_attestation_rejects_actual_bytecode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    bytecode = tmp_path / "actual.pyc"
    bytecode.write_bytes(b"executable bytecode")
    monkeypatch.setattr(manifest_policy.sys, "prefix", str(tmp_path))

    class BytecodeClaim:
        hash = None
        size = None

        @staticmethod
        def as_posix() -> str:
            return "package/__pycache__/actual.pyc"

    class BytecodeDistribution:
        files = (BytecodeClaim(),)

        @staticmethod
        def locate_file(_claim: object) -> Path:
            return bytecode

    monkeypatch.setattr(
        manifest_policy.importlib.metadata,
        "distribution",
        lambda _name: BytecodeDistribution(),
    )

    root = Path(__file__).resolve().parents[1]
    lock = json.loads(
        (root / "configs/submission_toolchain_lock_20260713.json").read_text(encoding="utf-8")
    )
    python_executable = Path(sys.executable).resolve()
    lock["python"] = {
        "implementation": manifest_policy.platform.python_implementation(),
        "version": manifest_policy.platform.python_version(),
        "platform": {
            "system": manifest_policy.platform.system(),
            "machine": manifest_policy.platform.machine(),
        },
        "executable": _file_lock(python_executable),
    }
    current_python_lock = tmp_path / "current-python-toolchain-lock.json"
    current_python_lock.write_text(json.dumps(lock), encoding="utf-8")
    with pytest.raises(ValueError, match="contains executable bytecode"):
        submission_toolchain_attestation(root, current_python_lock)


def test_submission_toolchain_attestation_rejects_legacy_schema(tmp_path: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    lock = json.loads(
        (root / "configs/submission_toolchain_lock_20260713.json").read_text(encoding="utf-8")
    )
    lock["schema_version"] = "walksafe.submission-toolchain.v1"
    legacy = tmp_path / "legacy-toolchain-lock.json"
    legacy.write_text(json.dumps(lock), encoding="utf-8")

    with pytest.raises(ValueError, match="unexpected submission toolchain lock schema"):
        submission_toolchain_attestation(root, legacy)


def _file_lock(path: Path) -> dict[str, object]:
    return {
        "resolved_path": str(path),
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
    }


def _tool_attestation(
    name: str,
    executable: Path,
    runtime_chain: object,
) -> dict[str, object]:
    return {
        "verified": {
            "tools": [
                {
                    "name": name,
                    **_file_lock(executable),
                    "runtime_chain": runtime_chain,
                }
            ]
        }
    }


def test_attested_tool_execution_ignores_fake_path(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    locked_dir = tmp_path / "locked-bin"
    locked_dir.mkdir()
    locked_marker = tmp_path / "locked-pdfinfo-ran"
    locked = locked_dir / "pdfinfo"
    locked.write_text('#!/bin/sh\ntouch "$1"\n', encoding="utf-8")
    locked.chmod(0o755)
    attestation = _tool_attestation("pdfinfo", locked, [])
    fake_dir = tmp_path / "fake-bin"
    fake_dir.mkdir()
    marker = tmp_path / "fake-pdfinfo-ran"
    fake = fake_dir / "pdfinfo"
    fake.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 77\n", encoding="utf-8")
    fake.chmod(0o755)
    monkeypatch.setenv("PATH", f"{fake_dir}:{os.environ.get('PATH', '')}")

    completed = run_attested_submission_tool(
        attestation,
        "pdfinfo",
        [str(locked_marker)],
        check=True,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0
    assert locked_marker.exists()
    assert not marker.exists()


def test_attested_tool_execution_rejects_symlink_target(tmp_path: Path) -> None:
    executable = tmp_path / "tool-real"
    executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    executable.chmod(0o755)
    link = tmp_path / "tool-link"
    link.symlink_to(executable)
    attestation = _tool_attestation("tool", link, [])
    with pytest.raises(ValueError, match="changed before execution"):
        run_attested_submission_tool(attestation, "tool", [], check=True)


def test_attested_tool_execution_checks_hash_after_failed_process(tmp_path: Path) -> None:
    executable = tmp_path / "tool"
    executable.write_text(
        '#!/bin/sh\nprintf "# changed\\n" >> "$0"\nexit 7\n',
        encoding="utf-8",
    )
    executable.chmod(0o755)
    attestation = _tool_attestation("tool", executable, [])

    with pytest.raises(ValueError, match="changed during execution"):
        run_attested_submission_tool(attestation, "tool", [], check=True)


@pytest.mark.parametrize("runtime_name", ["oosplash", "soffice.bin"])
def test_attested_tool_execution_checks_each_runtime_after_failed_process(
    runtime_name: str,
    tmp_path: Path,
) -> None:
    oosplash = tmp_path / "oosplash"
    oosplash.write_bytes(b"locked splash runtime")
    soffice_bin = tmp_path / "soffice.bin"
    soffice_bin.write_bytes(b"locked office runtime")
    executable = tmp_path / "soffice"
    executable.write_text(
        '#!/bin/sh\nprintf " changed" >> "$1"\nexit 7\n',
        encoding="utf-8",
    )
    executable.chmod(0o755)
    attestation = _tool_attestation(
        "libreoffice",
        executable,
        [_file_lock(oosplash), _file_lock(soffice_bin)],
    )

    with pytest.raises(ValueError, match="changed during execution"):
        run_attested_submission_tool(
            attestation,
            "libreoffice",
            [str(tmp_path / runtime_name)],
            check=True,
        )


@pytest.mark.parametrize("runtime_name", ["oosplash", "soffice.bin"])
def test_attested_tool_execution_rejects_each_changed_runtime_before_execution(
    runtime_name: str,
    tmp_path: Path,
) -> None:
    executable = tmp_path / "soffice"
    executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    executable.chmod(0o755)
    oosplash = tmp_path / "oosplash"
    oosplash.write_bytes(b"locked splash runtime")
    soffice_bin = tmp_path / "soffice.bin"
    soffice_bin.write_bytes(b"locked office runtime")
    attestation = _tool_attestation(
        "libreoffice",
        executable,
        [_file_lock(oosplash), _file_lock(soffice_bin)],
    )
    (tmp_path / runtime_name).write_bytes(b"changed runtime")

    with pytest.raises(ValueError, match="changed before execution"):
        run_attested_submission_tool(attestation, "libreoffice", [], check=True)


def test_attested_tool_execution_rejects_invalid_runtime_chain_shape(
    tmp_path: Path,
) -> None:
    executable = tmp_path / "soffice"
    executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    executable.chmod(0o755)
    oosplash = tmp_path / "oosplash"
    oosplash.write_bytes(b"locked splash runtime")
    soffice_bin = tmp_path / "soffice.bin"
    soffice_bin.write_bytes(b"locked office runtime")
    valid_chain = [_file_lock(oosplash), _file_lock(soffice_bin)]
    invalid_chains = (
        None,
        {"not": "a list"},
        [{**valid_chain[0], "unexpected": True}, valid_chain[1]],
        [{**valid_chain[0], "bytes": oosplash.stat().st_size + 1}, valid_chain[1]],
    )

    for runtime_chain in invalid_chains:
        attestation = _tool_attestation("libreoffice", executable, runtime_chain)
        with pytest.raises(ValueError, match="changed before execution"):
            run_attested_submission_tool(attestation, "libreoffice", [], check=True)


def test_attested_tool_execution_rejects_symlink_runtime(tmp_path: Path) -> None:
    executable = tmp_path / "soffice"
    executable.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    executable.chmod(0o755)
    runtime_target = tmp_path / "runtime-real"
    runtime_target.write_bytes(b"locked runtime")
    oosplash = tmp_path / "oosplash"
    oosplash.symlink_to(runtime_target)
    soffice_bin = tmp_path / "soffice.bin"
    soffice_bin.write_bytes(b"locked office runtime")
    runtime_lock = {**_file_lock(runtime_target), "resolved_path": str(oosplash)}
    attestation = _tool_attestation(
        "libreoffice",
        executable,
        [runtime_lock, _file_lock(soffice_bin)],
    )

    with pytest.raises(ValueError, match="changed before execution"):
        run_attested_submission_tool(attestation, "libreoffice", [], check=True)


@pytest.mark.parametrize("relation", ["missing_oosplash", "reversed"])
def test_attested_tool_execution_rejects_wrong_libreoffice_runtime_relation(
    relation: str,
    tmp_path: Path,
) -> None:
    marker = tmp_path / "launcher-ran"
    executable = tmp_path / "soffice"
    executable.write_text('#!/bin/sh\ntouch "$1"\n', encoding="utf-8")
    executable.chmod(0o755)
    oosplash = tmp_path / "oosplash"
    oosplash.write_bytes(b"locked splash runtime")
    soffice_bin = tmp_path / "soffice.bin"
    soffice_bin.write_bytes(b"locked office runtime")
    decoy = tmp_path / "pdfinfo"
    decoy.write_bytes(b"valid but unrelated runtime")
    runtime_chain = (
        [_file_lock(decoy), _file_lock(soffice_bin)]
        if relation == "missing_oosplash"
        else [_file_lock(soffice_bin), _file_lock(oosplash)]
    )
    attestation = _tool_attestation("libreoffice", executable, runtime_chain)

    with pytest.raises(ValueError, match="changed before execution"):
        run_attested_submission_tool(attestation, "libreoffice", [str(marker)], check=True)
    assert not marker.exists()
