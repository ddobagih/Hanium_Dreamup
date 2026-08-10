from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import sys

import pytest
from PIL import Image, ImageDraw, PngImagePlugin

from scripts.submission_build_io import (
    atomic_output_path,
    candidate_source_freeze_policy,
    verification_snapshot_boundary,
    verification_snapshot_policy_boundary,
)


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from build_submission_assets_20260710 import (  # noqa: E402
    android_device_architecture_body,
    evidence_results_spec,
    fitted_lines,
    font,
)
from build_design_documents_20260710 import (  # noqa: E402
    BUILD_DATE as DESIGN_BUILD_DATE,
    FIXED_TIME as DESIGN_FIXED_TIME,
    android_device_evidence_labels as design_device_evidence_labels,
    verification_snapshot_contract as design_snapshot_contract,
)
import build_submission_forms_20260710 as form_builder  # noqa: E402
from build_submission_forms_20260710 import (  # noqa: E402
    android_device_evidence as form_device_evidence,
)
from promote_submission_final_20260713 import (  # noqa: E402
    android_device_evidence as promotion_device_evidence,
)
import validate_submission_forms_20260710 as form_validator  # noqa: E402
from validate_submission_materials_20260710 import (  # noqa: E402
    find_forbidden_source_semantics,
    validate_core_semantic_facts,
    validate_evidence_results_semantics,
    validate_submission_source_semantics,
)


HISTORICAL_SOURCE_FREEZE_POLICY = (
    "이 snapshot은 2026-07-13 기준의 과거 계층 증거이며 현재 hardening 수치가 "
    "아니다. PASS와 FAIL을 함께 기록하고 Unit·격리 DB·build를 Field 또는 Release "
    "증거로 확대하지 않으며, source freeze 후 전체 재실행 수치로 교체한다. current "
    "source-freeze에서 Android device run이 없으면 NOT_RUN_CURRENT_SOURCE_FREEZE로 "
    "기록하고, 2026-07-13 SM-G981N 2/2는 별도 historical evidence로만 보존한다."
)
APPROVED_SOURCE_FREEZE_POLICY = (
    "사용자가 승인한 source-freeze commit의 자동검증 snapshot이며 최종 source-freeze "
    "근거로 채택했다. Field 또는 Release 증거로 확대하지 않고 PASS와 FAIL을 함께 "
    "기록하며 서로 다른 계층 수를 합산하지 않는다. current source-freeze Android "
    "device run은 NOT_RUN_CURRENT_SOURCE_FREEZE로 기록하고, 2026-07-13 SM-G981N "
    "2/2는 별도 historical evidence로만 보존한다."
)


def test_submission_builders_use_canonical_verification_counts(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    form_source = (ROOT / "scripts/build_submission_forms_20260710.py").read_text(encoding="utf-8")
    asset_source = (ROOT / "scripts/build_submission_assets_20260710.py").read_text(encoding="utf-8")
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(encoding="utf-8")
    )

    counts = facts["verification_snapshot"]["counts"]
    device = facts["verification_snapshot"]["android_device_verification"]
    bodies, rendered_fields = evidence_results_spec(facts)
    rendered_values = {
        item["fact_path"].rsplit(".", 1)[-1]: item["value"]
        for item in rendered_fields
        if ".counts." in item["fact_path"]
    }
    assert rendered_values == counts
    assert all(str(value) in "\n".join(bodies.values()).replace(",", "") for value in counts.values())
    assert "android_device_passed" not in counts
    assert "android_device_total" not in counts
    assert device["current_source_freeze"]["status"] == "NOT_RUN_CURRENT_SOURCE_FREEZE"
    assert device["current_source_freeze"]["executed_at"] is None
    assert device["current_source_freeze"]["passed"] is None
    assert device["current_source_freeze"]["total"] is None
    assert device["historical_evidence"]["status"] == "PASS_HISTORICAL_EVIDENCE"
    assert device["historical_evidence"]["executed_at"] == "2026-07-13"
    assert device["historical_evidence"]["passed"] == 2
    assert device["historical_evidence"]["total"] == 2
    assert "NOT_RUN_CURRENT_SOURCE_FREEZE" in facts["verification_snapshot"]["policy"]
    assert "historical evidence" in facts["verification_snapshot"]["policy"]
    rendered_by_path = {item["fact_path"]: item for item in rendered_fields}
    assert (
        rendered_by_path[
            "verification_snapshot.android_device_verification.current_source_freeze.status"
        ]["value"]
        == "NOT_RUN_CURRENT_SOURCE_FREEZE"
    )
    assert (
        rendered_by_path[
            "verification_snapshot.android_device_verification.historical_evidence.passed"
        ]["value"]
        == 2
    )
    assert "현재 source-freeze: NOT RUN" in bodies["android_device"]
    assert "2026-07-13" in bodies["android_device"]
    assert "2/2" in bodies["android_device"]
    assert facts["verification_snapshot"]["executed_at"] in bodies["snapshot_scope"]
    assert bodies["snapshot_scope"] == (
        "2026-07-16 승인된 source-freeze 검증 snapshot"
    )
    assert "VERIFICATION_COUNTS" in form_source
    for stale in (
        '"213", "Python unit"',
        '"532", "DB functional"',
        '"84", "integration"',
        '"125", "Voice"',
        '"206", "Android JVM"',
        "selected Python 213",
        "selected unit 213",
        "functional 532",
        "integration 84",
        "NFT 29 traces/754 files",
        "Voice 125",
        "Android JVM 206/206",
    ):
        assert stale not in form_source
        assert stale not in asset_source

    semantic_errors: list[str] = []
    validate_submission_source_semantics(ROOT, semantic_errors)
    assert semantic_errors == []
    assert find_forbidden_source_semantics("PostGIS 3 tables")
    assert not find_forbidden_source_semantics(
        "PostGIS 5 application tables; status/export/read 3종 audit"
    )

    docx_path = tmp_path / "submission.docx"
    pptx_path = tmp_path / "submission.pptx"
    monkeypatch.setattr(form_builder, "DOCX_OUTPUT", docx_path)
    monkeypatch.setattr(form_builder, "PPTX_OUTPUT", pptx_path)
    form_builder.build_docx()
    form_builder.build_pptx()
    rendered_texts = (
        form_validator.extract_docx_text(form_validator.Document(docx_path)),
        form_validator.extract_pptx_text(form_validator.Presentation(pptx_path)),
    )
    for rendered_text in rendered_texts:
        assert form_validator.REQUIRED_SCOPE_MARKERS[
            "verification snapshot time boundary"
        ].search(rendered_text)
        assert form_validator.REQUIRED_SCOPE_MARKERS["remote database verify-full"].search(
            rendered_text
        )
        assert form_validator.REQUIRED_SCOPE_MARKERS[
            "shared limiter and nonblocking readiness"
        ].search(rendered_text)
        for marker_name in (
            "Web non-metric advisory tier",
            "Android unsupported non-metric tier",
            "Web optional IMU",
            "Android fresh IMU required",
            "non-metric low directions and stability",
            "non-metric forbidden outputs",
            "non-metric report isolation",
            "existing Web risk and consent report preserved",
            "equal severity 3A delivery recheck",
            "non-metric Field open",
        ):
            assert form_validator.REQUIRED_SCOPE_MARKERS[marker_name].search(rendered_text)
    assert find_forbidden_source_semantics(
        "Voice pre-copy size/rate/duration/queue"
    )
    assert not find_forbidden_source_semantics(
        "Voice pre-copy auth/Content-Length/rate; post-copy duration/inference queue"
    )


def test_submission_snapshot_status_rejects_unknown_or_undisclosed_candidate() -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )

    unknown = copy.deepcopy(facts)
    unknown["verification_snapshot"]["status"] = "UNCLASSIFIED_2026-07-16"
    with pytest.raises(SystemExit, match="unsupported verification snapshot status"):
        evidence_results_spec(unknown)

    undisclosed = copy.deepcopy(facts)
    undisclosed["verification_snapshot"]["policy"] = "자동검증을 실행했다."
    errors: list[str] = []
    validate_core_semantic_facts(undisclosed, errors)
    assert any("approved source-freeze policy" in error for error in errors)

    approved_with_candidate_policy = copy.deepcopy(facts)
    approved_with_candidate_policy["verification_snapshot"]["policy"] = (
        candidate_source_freeze_policy("2026-07-16")
    )
    errors = []
    validate_core_semantic_facts(approved_with_candidate_policy, errors)
    assert any("approved source-freeze policy" in error for error in errors)


@pytest.mark.parametrize(
    ("status", "executed_at", "policy", "expected_error"),
    (
        (
            "SOURCE_FREEZE_CANDIDATE_2026-07-16",
            "2026-07-16",
            "후보가 아니며 최종 source-freeze 승인이다. "
            "NOT_RUN_CURRENT_SOURCE_FREEZE, historical evidence 2026-07-13.",
            "exact candidate policy",
        ),
        (
            "HISTORICAL_PRE_SOURCE_FREEZE_2026-07-13",
            "2026-07-13",
            "과거가 아니며 현재 hardening 수치가 아니다라는 문구와 source freeze를 "
            "적었지만 실제 최종 승인이다. NOT_RUN_CURRENT_SOURCE_FREEZE, "
            "historical evidence 2026-07-13.",
            "exact historical policy",
        ),
        (
            "HISTORICAL_FINAL_APPROVED",
            "2026-07-13",
            "과거 계층이며 현재 hardening 수치가 아니다. source freeze 후 교체한다. "
            "NOT_RUN_CURRENT_SOURCE_FREEZE, historical evidence 2026-07-13.",
            "unsupported verification snapshot status",
        ),
    ),
)
def test_core_snapshot_policy_rejects_contradictory_or_unstructured_non_final_state(
    status: str,
    executed_at: str,
    policy: str,
    expected_error: str,
) -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )
    facts["verification_snapshot"]["status"] = status
    facts["verification_snapshot"]["executed_at"] = executed_at
    facts["verification_snapshot"]["policy"] = policy

    errors: list[str] = []
    validate_core_semantic_facts(facts, errors)

    assert any(expected_error in error for error in errors), errors
    snapshot = facts["verification_snapshot"]
    with pytest.raises(ValueError, match=expected_error):
        verification_snapshot_policy_boundary(status, executed_at, policy)
    with pytest.raises(SystemExit, match=expected_error):
        evidence_results_spec(facts)
    with pytest.raises(ValueError, match=expected_error):
        form_validator.verification_snapshot_boundary_and_policy(snapshot)
    with pytest.raises(SystemExit, match=expected_error):
        form_builder.verification_snapshot_contract(snapshot)
    with pytest.raises(SystemExit, match=expected_error):
        design_snapshot_contract(snapshot)


def test_verification_snapshot_exact_contract_accepts_all_canonical_states() -> None:
    canonical = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )
    states = (
        (
            "SOURCE_FREEZE_CANDIDATE_2026-07-16",
            "2026-07-16",
            candidate_source_freeze_policy("2026-07-16"),
            "source-freeze 후보 검증 snapshot · 최종 승인 source-freeze 아님",
        ),
        (
            "HISTORICAL_PRE_SOURCE_FREEZE_2026-07-13",
            "2026-07-13",
            HISTORICAL_SOURCE_FREEZE_POLICY,
            "과거 pre-source-freeze snapshot · 현재 hardening 수치 아님",
        ),
        (
            "SOURCE_FREEZE_APPROVED_2026-07-16",
            "2026-07-16",
            APPROVED_SOURCE_FREEZE_POLICY,
            "승인된 source-freeze 검증 snapshot",
        ),
    )

    for status, executed_at, policy, expected_boundary in states:
        facts = copy.deepcopy(canonical)
        snapshot = facts["verification_snapshot"]
        snapshot.update(
            {"status": status, "executed_at": executed_at, "policy": policy}
        )

        errors: list[str] = []
        validate_core_semantic_facts(facts, errors)
        assert errors == []
        assert (
            verification_snapshot_policy_boundary(status, executed_at, policy)
            == expected_boundary
        )
        assert (
            form_validator.verification_snapshot_boundary_and_policy(snapshot)
            == expected_boundary
        )
        assert form_builder.verification_snapshot_contract(snapshot) == expected_boundary
        assert design_snapshot_contract(snapshot) == expected_boundary
        bodies, _ = evidence_results_spec(facts)
        assert bodies["snapshot_scope"] == f"{executed_at} {expected_boundary}"


def test_core_snapshot_rejects_impossible_as_of_date() -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )
    facts["as_of_date"] = "9999-99-99"

    errors: list[str] = []
    validate_core_semantic_facts(facts, errors)

    assert "canonical as_of_date must be an ISO date at or after verification execution" in errors


@pytest.mark.parametrize("executed_at", ["2026-7-1", "2026-02-30"])
def test_verification_snapshot_boundary_rejects_noncanonical_dates(
    executed_at: str,
) -> None:
    with pytest.raises(ValueError, match="ISO date"):
        verification_snapshot_boundary(
            f"SOURCE_FREEZE_CANDIDATE_{executed_at}",
            executed_at,
        )


def test_android_device_asset_copy_marks_time_boundary_and_fits_cards() -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )
    snapshot = facts["verification_snapshot"]
    bodies, _ = evidence_results_spec(facts)
    architecture_body = android_device_architecture_body(snapshot)
    assert "현재 source-freeze" in architecture_body
    assert "NOT RUN" in architecture_body
    assert "과거 2026-07-13" in architecture_body
    assert "SM-G981N 2/2 PASS" in architecture_body

    draw = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    for body, width, size, height in (
        (bodies["android_device"], 247, 14, 162),
        (architecture_body, 172, 12, 172),
    ):
        lines = fitted_lines(draw, body, width, size)
        assert all(draw.textbbox((0, 0), line, font=font(size))[2] <= width for line in lines)
        assert len(lines) * (size + 9) <= height


@pytest.mark.parametrize(
    ("mutate", "expected_error"),
    [
        (
            lambda value: value["current_source_freeze"].__setitem__("passed", 2),
            "canonical current source-freeze Android device verification contract drifted",
        ),
        (
            lambda value: value["current_source_freeze"].update(
                {"status": "PASS_CURRENT_SOURCE_FREEZE"}
            ),
            "canonical current source-freeze Android device verification contract drifted",
        ),
        (
            lambda value: value["current_source_freeze"].update({"status": None}),
            "canonical current source-freeze Android device verification contract drifted",
        ),
        (
            lambda value: value["historical_evidence"].__setitem__("passed", None),
            "canonical historical Android device evidence contract drifted",
        ),
        (
            lambda value: value["historical_evidence"].__setitem__("passed", 1),
            "canonical historical Android device evidence contract drifted",
        ),
        (
            lambda value: value["historical_evidence"].update(
                {"passed": 3, "total": 3}
            ),
            "canonical historical Android device evidence contract drifted",
        ),
        (
            lambda value: value["historical_evidence"].update(
                {"passed": 2.0, "total": 2.0}
            ),
            "canonical historical Android device evidence contract drifted",
        ),
        (
            lambda value: value["historical_evidence"].__setitem__(
                "device", "OTHER-DEVICE"
            ),
            "canonical historical Android device evidence contract drifted",
        ),
        (
            lambda value: value["historical_evidence"].__setitem__(
                "executed_at", "2026-07-12"
            ),
            "canonical historical Android device evidence contract drifted",
        ),
    ],
)
def test_android_device_verification_rejects_partial_null_or_status_mismatch(
    mutate: object,
    expected_error: str,
) -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )
    device = facts["verification_snapshot"]["android_device_verification"]
    mutate(device)  # type: ignore[operator]

    with pytest.raises(SystemExit, match="Android device"):
        evidence_results_spec(facts)
    with pytest.raises(SystemExit, match="Android device"):
        design_device_evidence_labels(facts["verification_snapshot"])
    with pytest.raises(SystemExit, match="Android device"):
        form_device_evidence(facts["verification_snapshot"])
    with pytest.raises(ValueError, match="Android device"):
        promotion_device_evidence(facts)

    errors: list[str] = []
    validate_core_semantic_facts(facts, errors)
    assert expected_error in errors


def test_flat_android_device_counts_are_rejected() -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(
            encoding="utf-8"
        )
    )
    facts["verification_snapshot"]["counts"].update(
        {"android_device_passed": 2, "android_device_total": 2}
    )

    with pytest.raises(SystemExit, match="exact positive integer count contract"):
        evidence_results_spec(facts)
    errors: list[str] = []
    validate_core_semantic_facts(facts, errors)
    assert (
        "canonical verification counts must exclude device evidence and remain positive"
        in errors
    )


def test_design_builder_uses_canonical_facts_date() -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(encoding="utf-8")
    )
    expected_date = facts["as_of_date"]

    assert DESIGN_BUILD_DATE == expected_date
    assert DESIGN_FIXED_TIME.strftime("%Y-%m-%d") == expected_date
    readme = (ROOT / "docs/submission/design_documents/README.md").read_text(encoding="utf-8")
    assert f"구현 사실 기준: {expected_date} canonical facts" in readme


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_evidence_semantic_fixture(root: Path, facts: dict[str, object]) -> tuple[Path, Path]:
    material_dir = root / "docs/submission/form_materials"
    asset_dir = material_dir / "assets"
    asset_dir.mkdir(parents=True)
    facts_path = material_dir / "09_제출_사실_기준.json"
    facts_path.write_text(json.dumps(facts, ensure_ascii=False) + "\n", encoding="utf-8")
    _, rendered_fields = evidence_results_spec(facts)
    image_path = asset_dir / "evidence_results.png"
    png_info = PngImagePlugin.PngInfo()
    png_info.add_text(
        "walksafe.evidence.rendered-fields.v1",
        json.dumps(
            rendered_fields,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ),
    )
    Image.new("RGB", (1600, 900), "white").save(image_path, pnginfo=png_info)
    sidecar_path = asset_dir / "evidence_results.semantic.json"
    sidecar_path.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.evidence-results-semantics.v1",
                "facts": {
                    "path": "docs/submission/form_materials/09_제출_사실_기준.json",
                    "schema_version": facts["schema_version"],
                    "sha256": _sha256(facts_path),
                },
                "artifact": {
                    "path": "docs/submission/form_materials/assets/evidence_results.png",
                    "sha256": _sha256(image_path),
                    "width": 1600,
                    "height": 900,
                },
                "rendered_fields": rendered_fields,
            },
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    return facts_path, sidecar_path


def test_evidence_semantic_sidecar_rejects_stale_canonical_count_mutation(tmp_path: Path) -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(encoding="utf-8")
    )
    facts_path, _sidecar_path = _write_evidence_semantic_fixture(tmp_path, facts)
    errors: list[str] = []
    validate_evidence_results_semantics(tmp_path, errors)
    assert errors == []

    mutated = copy.deepcopy(facts)
    mutated["verification_snapshot"]["counts"]["unit_python"] += 1
    facts_path.write_text(json.dumps(mutated, ensure_ascii=False) + "\n", encoding="utf-8")
    stale_errors: list[str] = []
    validate_evidence_results_semantics(tmp_path, stale_errors)
    assert "evidence semantic sidecar is stale against canonical facts" in stale_errors
    assert "evidence semantic rendered fields differ from canonical facts" in stale_errors
    assert "evidence PNG embedded semantics differ from canonical facts" in stale_errors

    core_errors: list[str] = []
    validate_core_semantic_facts(facts, core_errors)
    assert core_errors == []

    semantic_mutations: list[tuple[dict[str, object], str]] = []

    three_table_facts = copy.deepcopy(facts)
    three_table_facts["database"]["tables"] = three_table_facts["database"]["tables"][:3]
    semantic_mutations.append(
        (
            three_table_facts,
            "canonical database must contain the exact five application tables and roles",
        )
    )

    journal_facts = copy.deepcopy(facts)
    journal_facts["database"]["image_storage"] = "DB 밖 upload 파일"
    semantic_mutations.append(
        (
            journal_facts,
            "canonical report storage must retain durable journal and startup reconciliation",
        )
    )

    generic_privacy_facts = copy.deepcopy(facts)
    generic_privacy_facts["runtime"]["privacy"] = {
        "field_telemetry": "포괄 telemetry 동의 한 개"
    }
    semantic_mutations.append(
        (
            generic_privacy_facts,
            "canonical privacy must keep four distinct consent scopes",
        )
    )

    weak_tls_facts = copy.deepcopy(facts)
    weak_tls_facts["runtime"]["security"]["backend_database_transport"] = (
        "remote PostgreSQL sslmode=prefer"
    )
    semantic_mutations.append(
        (
            weak_tls_facts,
            "canonical runtime.security.backend_database_transport semantic contract drifted",
        )
    )

    multi_replica_voice_facts = copy.deepcopy(facts)
    multi_replica_voice_facts["runtime"]["voice"]["topology"] = "worker=4·replica=2"
    semantic_mutations.append(
        (
            multi_replica_voice_facts,
            "canonical runtime.voice.topology semantic contract drifted",
        )
    )

    unauthenticated_openapi_facts = copy.deepcopy(facts)
    unauthenticated_openapi_facts["runtime"]["api_contract"]["openapi"] = (
        "contracts/walksafe.openapi.json에 route만 기록"
    )
    semantic_mutations.append(
        (
            unauthenticated_openapi_facts,
            "canonical runtime.api_contract.openapi semantic contract drifted",
        )
    )

    for mutated_facts, expected_error in semantic_mutations:
        mutation_errors: list[str] = []
        validate_core_semantic_facts(mutated_facts, mutation_errors)
        assert expected_error in mutation_errors


def test_evidence_semantic_validator_rejects_png_without_embedded_contract(tmp_path: Path) -> None:
    facts = json.loads(
        (ROOT / "docs/submission/form_materials/09_제출_사실_기준.json").read_text(encoding="utf-8")
    )
    _facts_path, sidecar_path = _write_evidence_semantic_fixture(tmp_path, facts)
    image_path = tmp_path / "docs/submission/form_materials/assets/evidence_results.png"
    Image.new("RGB", (1600, 900), "white").save(image_path)
    sidecar = json.loads(sidecar_path.read_text(encoding="utf-8"))
    sidecar["artifact"]["sha256"] = _sha256(image_path)
    sidecar_path.write_text(json.dumps(sidecar, ensure_ascii=False) + "\n", encoding="utf-8")

    errors: list[str] = []
    validate_evidence_results_semantics(tmp_path, errors)

    assert "evidence PNG embedded semantics differ from canonical facts" in errors


def test_evergreen_submission_materials_do_not_pin_apk_build_hashes() -> None:
    text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / "docs/submission/form_materials").glob("*.md"))
    )
    assert "9ab74fd181c587f1ab46df08cc3839c7bb9641c253c80a35a083dee2b2e4c9e5" not in text
    assert "deaaa81863a4c981431ada91ec2f5ee5756bd2fdb493ccfdced9cc5e210d0824" not in text
    assert "clean-room release artifact receipt" in text


def test_atomic_output_replaces_complete_file(tmp_path: Path) -> None:
    target = tmp_path / "artifact.bin"
    target.write_bytes(b"old")

    with atomic_output_path(target) as temporary:
        temporary.write_bytes(b"complete-new-artifact")
        assert target.read_bytes() == b"old"

    assert target.read_bytes() == b"complete-new-artifact"
    assert list(tmp_path.glob(".artifact.bin.*.tmp")) == []


def test_atomic_output_preserves_previous_file_when_build_fails(tmp_path: Path) -> None:
    target = tmp_path / "artifact.bin"
    target.write_bytes(b"old")

    with pytest.raises(RuntimeError, match="build failed"):
        with atomic_output_path(target) as temporary:
            temporary.write_bytes(b"partial")
            raise RuntimeError("build failed")

    assert target.read_bytes() == b"old"
    assert list(tmp_path.glob(".artifact.bin.*.tmp")) == []
