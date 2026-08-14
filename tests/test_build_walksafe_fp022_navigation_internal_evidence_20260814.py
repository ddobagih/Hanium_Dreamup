from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from scripts import build_walksafe_fp022_navigation_internal_evidence_20260814 as builder


EXECUTOR_ID = "CODEX-FP022-IMPLEMENTER-001"
EXECUTOR_TASK = "/root"
REVIEWER_ID = "CODEX-FP022-REVIEWER-001"
REVIEWER_TASK = "/root/fp022_review"


def _write(root: Path, relative: str, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _groups(root: Path) -> tuple[builder.SourceGroup, ...]:
    groups = (
        builder.SourceGroup("ANDROID_RUNTIME", ("product/MainActivity.kt",)),
        builder.SourceGroup("ANDROID_INTERNAL_TESTS", ("product/NavigationTest.kt",)),
        builder.SourceGroup("POLICY_DOCS", ("docs/navigation.md",)),
    )
    for index, group in enumerate(groups, start=1):
        _write(root, group.paths[0], f"content-{index}\n".encode())
    return groups


def _authority() -> dict[str, object]:
    return {
        "goal_binding": {
            "role": "FP022_GOAL",
            "path": builder.GOAL_REL.as_posix(),
            "byte_length": 10,
            "sha256": "a" * 64,
        },
        "contract_binding": {
            "role": "FP022_START_GATE_CONTRACT_R002",
            "path": builder.CONTRACT_REL.as_posix(),
            "byte_length": 11,
            "sha256": "b" * 64,
        },
        "start_gate_binding": {
            "role": "FP022_EXACT7_START_GATE",
            "path": builder.START_GATE_REL.as_posix(),
            "byte_length": 12,
            "sha256": "c" * 64,
            "event_sequence": 69,
            "event_id": builder.START_EVENT_ID,
        },
        "gate_ended_at": "2026-08-14T13:28:02+09:00",
    }


def _lane_material() -> tuple[dict[str, dict[str, object]], dict[str, bytes]]:
    observations: dict[str, dict[str, object]] = {}
    raw_outputs: dict[str, bytes] = {}
    passed_by_lane = {
        "BACKEND_NAVIGATION_INTERNAL": 42,
        "ANDROID_USER_INTERNAL": 1000,
        "TEST_LAYER_REGISTRY_VALIDATE": 1,
    }
    marker_by_lane = {
        "BACKEND_NAVIGATION_INTERNAL": "42 passed in 0.62s",
        "ANDROID_USER_INTERNAL": (
            "> Task :app:testDebugUnitTest\n"
            "> Task :app:assembleDebug\n"
            "> Task :app:lintDebug\n"
            "BUILD SUCCESSFUL in 1m 4s"
        ),
        "TEST_LAYER_REGISTRY_VALIDATE": "TEST_LAYER_REGISTRY_VALIDATE: PASS",
    }
    for index, lane in enumerate(builder.LANES, start=1):
        passed = passed_by_lane[lane.lane_id]
        started_at = f"2026-08-14T14:0{index}:00+09:00"
        ended_at = f"2026-08-14T14:0{index}:30+09:00"
        raw = (
            f"WALKSAFE_FP022_COMMAND {lane.expected_command}\n"
            f"WALKSAFE_FP022_STARTED_AT {started_at}\n"
            f"{marker_by_lane[lane.lane_id]}\n"
            f"WALKSAFE_FP022_SUMMARY passed={passed} failed=0 errors=0 skipped=0\n"
            "WALKSAFE_FP022_EXIT_CODE 0\n"
            f"WALKSAFE_FP022_ENDED_AT {ended_at}\n"
        ).encode()
        raw_outputs[lane.lane_id] = raw
        observations[lane.lane_id] = {
            "schema_version": "walksafe.fp022-internal-lane-observation.v1",
            "lane_id": lane.lane_id,
            "status": "PASS",
            "command": lane.expected_command,
            "exit_code": 0,
            "started_at": started_at,
            "ended_at": ended_at,
            "raw_output_sha256": builder.bytes_sha256(raw),
            "raw_output_byte_length": len(raw),
            "metrics": {
                "passed": passed,
                "failed": 0,
                "errors": 0,
                "skipped": 0,
            },
        }
    return observations, raw_outputs


def _evidence(root: Path, groups: tuple[builder.SourceGroup, ...]) -> dict[Path, str]:
    observations, raw_outputs = _lane_material()
    return builder.build_evidence_outputs(
        root=root,
        lane_observations=observations,
        lane_raw_outputs=raw_outputs,
        source_groups=groups,
        authority=_authority(),
        executor_actor_id=EXECUTOR_ID,
        executor_task=EXECUTOR_TASK,
    )


def _r028(*, gap_status: str = "PARTIAL") -> dict[Path, str | bytes]:
    gap = builder.sealed(
        {
            "schema_version": "walksafe.implementation-gap-analysis.v1",
            "assessments": [
                {
                    "gap_id": "GAP-031",
                    "source_policy_id": "FP-022",
                    "status": gap_status,
                    "formal_test_status": "NOT_RUN",
                }
            ],
        },
        "report_content_sha256",
    )
    gap_text = builder.json_text(gap)
    backlog = builder.sealed(
        {
            "schema_version": "walksafe.implementation-remediation-backlog.v1",
            "gap_report_content_sha256": gap["report_content_sha256"],
            "next_action_sequence": [
                {
                    "source_policy_id": "FP-022",
                    "status": gap_status,
                    "order": 24,
                },
                {
                    "source_policy_id": "FP-023",
                    "status": "CONFLICTING",
                    "order": 25,
                },
            ],
        },
        "backlog_content_sha256",
    )
    return {
        builder.R028_GAP_JSON_REL: gap_text,
        builder.R028_GAP_MD_REL: b"# R028 GAP-031\n\nPARTIAL\n",
        builder.R028_BACKLOG_JSON_REL: builder.json_text(backlog),
        builder.R028_BACKLOG_MD_REL: b"# R028 backlog\n\nFP-023 next\n",
    }


def _successor(
    root: Path,
    groups: tuple[builder.SourceGroup, ...],
    evidence: dict[Path, str],
    r028: dict[Path, str | bytes],
) -> dict[Path, str]:
    return builder.build_successor_outputs(
        evidence_outputs=evidence,
        r028_outputs=r028,
        root=root,
        source_groups=groups,
        authority=_authority(),
        executor_actor_id=EXECUTOR_ID,
        executor_task=EXECUTOR_TASK,
        required_reviewer_actor_id=REVIEWER_ID,
        required_reviewer_task=REVIEWER_TASK,
    )


def _independent_review(subject_text: str) -> bytes:
    subject = json.loads(subject_text)
    review = builder.sealed(
        {
            "schema_version": "walksafe.fp022-internal-independent-review.v1",
            "document_id": (
                "WS-FP022-NAVIGATION-INTERNAL-INDEPENDENT-REVIEW-20260814-001"
            ),
            "goal_id": builder.GOAL_ID,
            "kind": "INTERNAL_INDEPENDENT_REVIEW",
            "status": "PASS",
            "decision": "APPROVED",
            "decided_at": "2026-08-14T15:00:00+09:00",
            "executor": {
                "id": EXECUTOR_ID,
                "task": EXECUTOR_TASK,
                "role": "INTERNAL_IMPLEMENTATION_EXECUTOR",
            },
            "reviewer": {
                "id": REVIEWER_ID,
                "task": REVIEWER_TASK,
                "role": "SEPARATE_INTERNAL_REVIEWER",
                "separate_from_executor": True,
                "external_independence_claimed": False,
                "decision": "APPROVED",
            },
            "review_subject_binding": {
                "path": builder.REVIEW_SUBJECT_REL.as_posix(),
                "byte_length": len(subject_text.encode()),
                "sha256": builder.bytes_sha256(subject_text.encode()),
            },
            "reviewed_evidence_bindings": subject["reviewed_evidence_bindings"],
            "findings": [],
            "external_independence_claimed": False,
            "completion_boundary": builder.completion_boundary(),
        },
        "independent_review_content_sha256",
    )
    return builder.json_text(review).encode()


def test_three_stages_are_deterministic_content_bound_and_ordered(tmp_path: Path) -> None:
    groups = _groups(tmp_path)
    evidence = _evidence(tmp_path, groups)
    assert tuple(evidence) == (
        *(lane.log_rel for lane in builder.LANES),
        builder.IMPLEMENTATION_REL,
        builder.OBSERVATIONS_REL,
        builder.VERIFICATION_REL,
    )
    assert evidence == _evidence(tmp_path, groups)
    implementation = json.loads(evidence[builder.IMPLEMENTATION_REL])
    verification = json.loads(evidence[builder.VERIFICATION_REL])
    manifest = implementation["final_content_manifest"]
    assert implementation["credit_scope"] == "REPOSITORY_INTERNAL_ONLY"
    assert verification["credit_scope"] == "REPOSITORY_INTERNAL_ONLY"
    assert manifest["exact_path_count"] == 3
    for row in manifest["files"]:
        raw = (tmp_path / row["path"]).read_bytes()
        assert row["byte_length"] == len(raw)
        assert row["sha256"] == builder.bytes_sha256(raw)

    r028 = _r028()
    successor = _successor(tmp_path, groups, evidence, r028)
    assert tuple(successor) == (builder.SUCCESSOR_REL, builder.REVIEW_SUBJECT_REL)
    successor_doc = json.loads(successor[builder.SUCCESSOR_REL])
    subject = json.loads(successor[builder.REVIEW_SUBJECT_REL])
    assert successor_doc["gap031_status"] == "PARTIAL"
    assert successor_doc["next_goal"]["priority_rank"] == 25
    assert successor_doc["next_single_action"] == "SEPARATE_INTERNAL_REVIEW"
    assert subject["status"] == "REVIEW_PENDING"
    assert len(subject["reviewed_evidence_bindings"]) == 11

    review_raw = _independent_review(successor[builder.REVIEW_SUBJECT_REL])
    completion = builder.build_completion_output(
        evidence_outputs=evidence,
        successor_outputs=successor,
        r028_outputs=r028,
        independent_review_raw=review_raw,
        executor_actor_id=EXECUTOR_ID,
        executor_task=EXECUTOR_TASK,
        reviewer_actor_id=REVIEWER_ID,
        reviewer_task=REVIEWER_TASK,
    )
    assert tuple(completion) == (builder.COMPLETION_REL,)
    receipt = json.loads(completion[builder.COMPLETION_REL])
    assert receipt["status"] == "ACCEPTED"
    assert receipt["result"] == "PASS"
    assert receipt["next_goal"]["goal_id"] == "WS-GOAL-EPIC-04-FP-023-R001"
    boundary = receipt["completion_boundary"]
    assert boundary["formal_test_status"] == "NOT_RUN"
    assert boundary["actual_device_status"] == "NOT_RUN"
    assert boundary["external_tmap_status"] == "NOT_RUN"
    assert boundary["production_deployment_status"] == "NOT_RUN"
    assert boundary["release_status"] == "NOT_ELIGIBLE"
    assert boundary["formal_test_credit_delta"] == 0
    assert boundary["device_credit_delta"] == 0
    assert boundary["external_credit_delta"] == 0
    assert boundary["deployment_credit_delta"] == 0
    assert boundary["release_credit_delta"] == 0

    (tmp_path / "product/MainActivity.kt").write_bytes(b"drift\n")
    with pytest.raises(builder.BuildError, match="current file binding differs"):
        builder.validate_evidence_outputs(
            evidence,
            root=tmp_path,
            source_groups=groups,
            authority=_authority(),
            executor_actor_id=EXECUTOR_ID,
            executor_task=EXECUTOR_TASK,
        )


def test_raw_command_success_and_r028_gates_fail_closed(tmp_path: Path) -> None:
    groups = _groups(tmp_path)
    observations, raw_outputs = _lane_material()
    observations["BACKEND_NAVIGATION_INTERNAL"] = deepcopy(
        observations["BACKEND_NAVIGATION_INTERNAL"]
    )
    observations["BACKEND_NAVIGATION_INTERNAL"]["command"] = "pytest anything"
    with pytest.raises(builder.BuildError, match="command differs"):
        builder.build_evidence_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=raw_outputs,
            source_groups=groups,
            authority=_authority(),
            executor_actor_id=EXECUTOR_ID,
            executor_task=EXECUTOR_TASK,
        )

    observations, raw_outputs = _lane_material()
    raw = raw_outputs["TEST_LAYER_REGISTRY_VALIDATE"].replace(
        b"TEST_LAYER_REGISTRY_VALIDATE: PASS",
        b"registry output without pass marker",
    )
    raw_outputs["TEST_LAYER_REGISTRY_VALIDATE"] = raw
    observations["TEST_LAYER_REGISTRY_VALIDATE"]["raw_output_sha256"] = (
        builder.bytes_sha256(raw)
    )
    observations["TEST_LAYER_REGISTRY_VALIDATE"]["raw_output_byte_length"] = len(raw)
    with pytest.raises(builder.BuildError, match="success marker missing"):
        builder.build_evidence_outputs(
            root=tmp_path,
            lane_observations=observations,
            lane_raw_outputs=raw_outputs,
            source_groups=groups,
            authority=_authority(),
            executor_actor_id=EXECUTOR_ID,
            executor_task=EXECUTOR_TASK,
        )

    evidence = _evidence(tmp_path, groups)
    with pytest.raises(builder.BuildError, match="GAP-031 reassessment differs"):
        _successor(tmp_path, groups, evidence, _r028(gap_status="CONFLICTING"))


def test_review_actor_task_and_approved_review_are_mandatory(tmp_path: Path) -> None:
    groups = _groups(tmp_path)
    evidence = _evidence(tmp_path, groups)
    r028 = _r028()
    with pytest.raises(builder.BuildError, match="review actor must differ"):
        builder.build_successor_outputs(
            evidence_outputs=evidence,
            r028_outputs=r028,
            root=tmp_path,
            source_groups=groups,
            authority=_authority(),
            executor_actor_id=EXECUTOR_ID,
            executor_task=EXECUTOR_TASK,
            required_reviewer_actor_id=EXECUTOR_ID,
            required_reviewer_task=REVIEWER_TASK,
        )
    with pytest.raises(builder.BuildError, match="review task must differ"):
        builder.build_successor_outputs(
            evidence_outputs=evidence,
            r028_outputs=r028,
            root=tmp_path,
            source_groups=groups,
            authority=_authority(),
            executor_actor_id=EXECUTOR_ID,
            executor_task=EXECUTOR_TASK,
            required_reviewer_actor_id=REVIEWER_ID,
            required_reviewer_task=EXECUTOR_TASK,
        )

    successor = _successor(tmp_path, groups, evidence, r028)
    review_raw = _independent_review(successor[builder.REVIEW_SUBJECT_REL])
    review = json.loads(review_raw)
    review["decision"] = "REJECTED"
    review.pop("independent_review_content_sha256")
    rejected = builder.json_text(
        builder.sealed(review, "independent_review_content_sha256")
    ).encode()
    with pytest.raises(builder.BuildError, match="independent-review boundary differs"):
        builder.build_completion_output(
            evidence_outputs=evidence,
            successor_outputs=successor,
            r028_outputs=r028,
            independent_review_raw=rejected,
            executor_actor_id=EXECUTOR_ID,
            executor_task=EXECUTOR_TASK,
            reviewer_actor_id=REVIEWER_ID,
            reviewer_task=REVIEWER_TASK,
        )


def test_default_source_scope_is_exact_current_fp022_change_set() -> None:
    paths = [path for group in builder.IMPLEMENTATION_SOURCE_GROUPS for path in group.paths]
    assert len(paths) == 22
    assert paths == [
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/LocationPolicy.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/StepLengthEstimator.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicy.kt",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/WalkingRouteModels.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityNavigationCompositionTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommandTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClientTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClientNetworkTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/LocationPolicyTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigatorTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/StepLengthEstimatorTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/TactileRoutePolicyTest.kt",
        "backend/app/api/navigation.py",
        "backend/app/services/tmap_pedestrian.py",
        "backend/app/services/walking_route_sanity.py",
        "backend/tests/test_navigation_routes.py",
        "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/README.md",
        "docs/walksafe-v2/navigation_integration_policy.md",
    ]


def test_capture_runs_exact_lanes_serially_counts_xml_and_filters_secret_env(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result_dir = tmp_path / "apps/android/app/build/test-results/testDebugUnitTest"
    result_dir.mkdir(parents=True)
    testcases = "".join(f'<testcase name="t{index}"/>' for index in range(1000))
    (result_dir / "TEST-suite.xml").write_text(
        f'<testsuite tests="1000">{testcases}</testsuite>',
        encoding="utf-8",
    )
    calls: list[tuple[tuple[str, ...], Path, dict[str, str]]] = []
    outputs = (
        b".......................................... [100%]\n42 passed in 0.62s\n",
        (
            b"> Task :app:testDebugUnitTest\n"
            b"> Task :app:assembleDebug\n"
            b"> Task :app:lintDebug\n"
            b"BUILD SUCCESSFUL in 1s\n"
        ),
        b"",
    )

    def fake_run(
        argv: tuple[str, ...],
        *,
        cwd: Path,
        env: dict[str, str],
        stdout: object,
        stderr: object,
        check: bool,
    ) -> SimpleNamespace:
        del stdout, stderr, check
        calls.append((tuple(argv), Path(cwd), dict(env)))
        return SimpleNamespace(returncode=0, stdout=outputs[len(calls) - 1])

    monkeypatch.setenv("TMAP_APP_KEY", "must-not-reach-child")
    monkeypatch.setattr(builder.subprocess, "run", fake_run)
    observations, raw_outputs = builder.capture_lane_observations(tmp_path)
    assert [call[0][0] for call in calls] == [
        builder.LOCKED_TEST_PYTHON,
        "./gradlew",
        "bash",
    ]
    assert all("TMAP_APP_KEY" not in call[2] for call in calls)
    assert observations["ANDROID_USER_INTERNAL"]["metrics"]["passed"] == 1000
    assert b"WALKSAFE_FP022_SUMMARY passed=1000" in raw_outputs[
        "ANDROID_USER_INTERNAL"
    ]
