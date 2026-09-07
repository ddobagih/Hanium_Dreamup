from __future__ import annotations

from pathlib import Path

import pytest

from scripts import check_walksafe_active_docs as subject


ROOT = Path(__file__).resolve().parents[1]


def test_active_document_discovery_excludes_control_and_deliverable_evidence(
    tmp_path: Path,
) -> None:
    current = tmp_path / "docs" / "guides" / "current.md"
    control = tmp_path / "docs" / "control" / "history.md"
    deliverable = tmp_path / "docs" / "deliverables" / "evidence.md"
    for path in (current, control, deliverable):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("# Fixture\n", encoding="utf-8")

    selected = subject.active_documents(tmp_path)

    assert Path("docs/guides/current.md") in selected
    assert Path("docs/control/goals/README.md") in selected
    assert Path("docs/control/history.md") not in selected
    assert Path("docs/deliverables/evidence.md") not in selected


def test_active_document_discovery_includes_mutable_android_readmes() -> None:
    selected = subject.active_documents(ROOT)

    assert Path("apps/android/app/README.md") in selected
    assert Path("apps/android/adminapp/README.md") in selected
    assert (
        Path(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
            "walksafe/report/README.md"
        )
        in selected
    )
    assert Path("apps/android/README.md") not in selected


def test_current_status_contracts_follow_checkpoint(tmp_path: Path) -> None:
    paths = (subject.CHECKPOINT, *subject.CURRENT_STATUS_DOCUMENTS)
    for relative in paths:
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())

    assert subject._check_current_status_contracts(tmp_path) == []

    readme = tmp_path / "README.md"
    correct_release = "- 출시 상태: `NOT_ELIGIBLE`"
    readme.write_text(
        readme.read_text(encoding="utf-8").replace(
            "Goal package: v2.4 `ACTIVE`",
            "Goal package: v2.4 `STALE`",
        ),
        encoding="utf-8",
    )

    assert any(
        "README.md: current status values conflict with checkpoint" in error
        for error in subject._check_current_status_contracts(tmp_path)
    )

    readme.write_bytes((ROOT / "README.md").read_bytes())
    readme.write_text(
        readme.read_text(encoding="utf-8").replace(
            correct_release,
            correct_release + " → `ELIGIBLE`",
        ),
        encoding="utf-8",
    )

    assert any(
        "README.md: current status values conflict with checkpoint" in error
        for error in subject._check_current_status_contracts(tmp_path)
    )

    readme.write_bytes((ROOT / "README.md").read_bytes())
    readme.write_text(
        readme.read_text(encoding="utf-8").replace(
            correct_release,
            "- 출시 상태: `ELIGIBLE`",
        )
        + f"\n<!-- {correct_release} -->\n"
        + "과거 상태 기록: 출시 상태: `NOT_ELIGIBLE`\n",
        encoding="utf-8",
    )

    assert any(
        "README.md: current status values conflict with checkpoint" in error
        for error in subject._check_current_status_contracts(tmp_path)
    )


def test_current_operational_docs_keep_exact_environment_and_source_facts() -> None:
    development = (
        ROOT / "docs/guides/development-environment-guide.md"
    ).read_text(encoding="utf-8")
    backend_deployment = (
        ROOT / "deploy/config/walksafe-backend.env.example"
    ).read_text(encoding="utf-8")
    backend_example = (ROOT / "backend/.env.example").read_text(encoding="utf-8")
    tests_readme = (ROOT / "tests/README.md").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/quality.yml").read_text(encoding="utf-8")
    for token in (
        "https://nodejs.org/dist/v22.23.1/node-v22.23.1-linux-x64.tar.xz",
        "9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578",
        "tar --extract --xz --same-permissions",
        "scripts/check_walksafe_node_toolchain_20260715.py",
    ):
        assert token in development
        assert token in workflow
    for token in (
        'test "$(uname -s)" = "Linux"',
        'test "$(uname -m)" = "x86_64"',
        'WALKSAFE_NODE_ROOT="$(mktemp -d)" || exit 1',
        'WALKSAFE_NODE_ARCHIVE="$(mktemp)" || exit 1',
        'test -d "${WALKSAFE_NODE_ROOT:?}"',
        'test -f "${WALKSAFE_NODE_ARCHIVE:?}"',
    ):
        assert token in development
    assert 'test -x "${WALKSAFE_NODE_BIN_DIR:?}/node"' in tests_readme
    assert 'node_executable="$(command -v node)"' not in tests_readme
    assert (
        "Backend와 Android Gateway 프로세스의 `WALKSAFE_GATEWAY_SESSION_SECRET`를 "
        "정확히 같은 비밀값으로 설정"
    ) in development
    for assignment in (
        "WALKSAFE_SIGNUP_RAW_ORIGINAL_VERSION=FP-013-RAW-1.1.0",
        "WALKSAFE_SIGNUP_AUTOMATIC_REPORTING_VERSION=FP-013-AUTO-1.1.0",
        "WALKSAFE_SIGNUP_TRAINING_REUSE_VERSION=FP-013-TRAINING-1.1.0",
    ):
        assert assignment in backend_deployment
        assert assignment in backend_example

    package_readme = (
        ROOT
        / "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/README.md"
    ).read_text(encoding="utf-8")
    report_readme = (
        ROOT
        / "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/report/README.md"
    ).read_text(encoding="utf-8")
    navigation_readme = (
        ROOT
        / "apps/android/app/src/main/java/kr/co/hanium/dreamup/"
        "walksafe/navigation/README.md"
    ).read_text(encoding="utf-8")
    assert "navigation/Gateway route" in package_readme
    assert "검증된 첫 실행 actor binding" in package_readme
    assert "현재 로컬 입력값" not in package_readme
    assert "활성 Gateway session actor와 일치하는 `reporter_user_id`" in report_readme
    assert "비어 있지 않은 local `reporter_user_id`" not in report_readme
    assert "Gateway 경로 client" in navigation_readme
    assert "현재 구현은 GPS 필터와 보폭 보정이다" not in navigation_readme

    phase_log = (
        ROOT
        / "docs/planning/repository-modernization-20260811/phase-review-log.md"
    ).read_text(encoding="utf-8")
    assert "5단계 게시 종료 확인:" in phase_log
    assert "`31506054781`" in phase_log
    assert "저장소 현대화 5단계는 `PASS`로 닫는다" in phase_log


def test_active_docs_accept_valid_relative_links_scripts_and_runner_selectors(
    tmp_path: Path,
) -> None:
    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts" / "example.py").write_text("\n", encoding="utf-8")
    runner = tmp_path / "scripts" / "run_walksafe_test_layers_current.sh"
    runner.write_text(
        """#!/usr/bin/env bash
case "${1:-all}" in
  validate) ;;
  unit) ;;
  all) ;;
  *) echo "Usage: $0 {validate|unit|all}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    guide = tmp_path / "docs" / "guide.md"
    guide.parent.mkdir()
    guide.write_text(
        """# Guide

[section](target.md#target-section)
`scripts/example.py`
`scripts/run_walksafe_test_layers_current.sh validate`
`scripts/run_walksafe_test_layers_current.sh unit`
`scripts/run_walksafe_test_layers_current.sh all`
""",
        encoding="utf-8",
    )
    (guide.parent / "target.md").write_text(
        "# Target section\n", encoding="utf-8"
    )

    result = subject.check_active_docs(tmp_path, (Path("docs/guide.md"),))

    assert result.errors == ()
    assert result.local_link_count == 1
    assert result.script_reference_count == 4
    assert result.runner_selectors == ("all", "unit", "validate")


def test_active_docs_report_missing_path_fragment_script_and_selector(
    tmp_path: Path,
) -> None:
    (tmp_path / "scripts").mkdir()
    runner = tmp_path / "scripts" / "run_walksafe_test_layers_current.sh"
    runner.write_text(
        """#!/usr/bin/env bash
case "${1:-all}" in
  validate) ;;
  *) echo "Usage: $0 {validate}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    guide = tmp_path / "guide.md"
    guide.write_text(
        """# Guide

[missing](missing.md)
[bad fragment](target.md#absent)
`scripts/not-there.py`
`scripts/run_walksafe_test_layers_current.sh model-audit`
""",
        encoding="utf-8",
    )
    (tmp_path / "target.md").write_text("# Present\n", encoding="utf-8")

    result = subject.check_active_docs(tmp_path, (Path("guide.md"),))

    assert any("missing.md: path does not exist" in error for error in result.errors)
    assert any("target.md#absent: fragment does not exist" in error for error in result.errors)
    assert any("scripts/not-there.py: script does not exist" in error for error in result.errors)
    assert any("model-audit: documented selector is missing" in error for error in result.errors)


def test_runner_case_and_usage_must_match(tmp_path: Path) -> None:
    (tmp_path / "scripts").mkdir()
    runner = tmp_path / "scripts" / "run_walksafe_test_layers_current.sh"
    runner.write_text(
        """#!/usr/bin/env bash
case "${1:-all}" in
  validate) ;;
  unit) ;;
  *) echo "Usage: $0 {validate}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    guide = tmp_path / "guide.md"
    guide.write_text(
        """# Guide

`scripts/run_walksafe_test_layers_current.sh validate`
`scripts/run_walksafe_test_layers_current.sh unit`
""",
        encoding="utf-8",
    )

    result = subject.check_active_docs(tmp_path, (Path("guide.md"),))

    assert any("runner case/usage selectors differ" in error for error in result.errors)


def test_selector_with_underscore_is_not_truncated(tmp_path: Path) -> None:
    (tmp_path / "scripts").mkdir()
    runner = tmp_path / subject.RUNNER
    runner.write_text(
        """#!/usr/bin/env bash
case "${1:-validate}" in
  validate) ;;
  *) echo "Usage: $0 {validate}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    guide = tmp_path / "guide.md"
    guide.write_text(
        "`scripts/run_walksafe_test_layers_current.sh unit_typo`\n",
        encoding="utf-8",
    )

    result = subject.check_active_docs(tmp_path, (Path("guide.md"),))

    assert any("unit_typo: documented selector is missing" in error for error in result.errors)


@pytest.mark.parametrize(
    "command",
    (
        "`scripts/run_walksafe_test_layers_20260711.sh validate`",
        "`./scripts/run_walksafe_test_layers_20260711.sh validate`",
        "`bash scripts/run_walksafe_test_layers_20260711.sh validate`",
        "`../scripts/run_walksafe_test_layers_20260711.sh validate`",
        '"scripts/run_walksafe_test_layers_20260711.sh" validate',
        'bash "scripts/run_walksafe_test_layers_20260711.sh" validate',
        "scripts/run_walksafe_test_layers_20260711.sh validate/extra",
        "scripts/run_walksafe_test_layers_20260711.sh --help",
        "bash scripts/run_walksafe_test_layers_20260711.sh",
        "./scripts/run_walksafe_test_layers_20260711.sh",
        "scripts/run_walksafe_test_layers_20260711.sh | cat",
        "scripts/run_walksafe_test_layers_20260711.sh && true",
        "././scripts/run_walksafe_test_layers_20260711.sh",
        '"$PWD/scripts/run_walksafe_test_layers_20260711.sh"',
        r"scripts/run_walksafe_test_layers_20260711\.sh validate",
        'scripts/run_walksafe_test_layers_20260711"."sh validate',
        "[scripts/run_walksafe_test_layers_20260711.sh validate](guide.md)",
    ),
)
def test_historical_runner_commands_are_rejected(tmp_path: Path, command: str) -> None:
    (tmp_path / "scripts").mkdir()
    current = tmp_path / subject.RUNNER
    current.write_text(
        """#!/usr/bin/env bash
case "${1:-validate}" in
  validate) ;;
  *) echo "Usage: $0 {validate}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    historical = tmp_path / subject.HISTORICAL_RUNNER
    historical.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
    guide = tmp_path / "guide.md"
    guide.write_text(
        f"`scripts/run_walksafe_test_layers_current.sh validate`\n{command}\n",
        encoding="utf-8",
    )

    result = subject.check_active_docs(tmp_path, (Path("guide.md"),))

    assert any("historical runner command" in error and "is forbidden" in error for error in result.errors)


def test_historical_runner_link_without_command_is_allowed(tmp_path: Path) -> None:
    (tmp_path / "scripts").mkdir()
    current = tmp_path / subject.RUNNER
    current.write_text(
        """#!/usr/bin/env bash
case "${1:-validate}" in
  validate) ;;
  *) echo "Usage: $0 {validate}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    historical = tmp_path / subject.HISTORICAL_RUNNER
    historical.write_text("#!/usr/bin/env bash\n", encoding="utf-8")
    guide = tmp_path / "guide.md"
    guide.write_text(
        "`scripts/run_walksafe_test_layers_current.sh validate`\n"
        "[scripts/run_walksafe_test_layers_20260711.sh]"
        "(scripts/run_walksafe_test_layers_20260711.sh)\n",
        encoding="utf-8",
    )

    result = subject.check_active_docs(tmp_path, (Path("guide.md"),))

    assert result.errors == ()


def test_fenced_shell_command_substitution_cannot_run_historical_runner(
    tmp_path: Path,
) -> None:
    errors, selectors = subject._runner_commands(
        Path("guide.md"),
        "```bash\nout=`scripts/run_walksafe_test_layers_20260711.sh`\n```\n",
    )

    assert selectors == set()
    assert len(errors) == 1
    assert "historical runner command is forbidden" in errors[0]


def test_script_reference_check_ignores_urls_and_rejects_repository_escape(
    tmp_path: Path,
) -> None:
    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts" / "valid.py").write_text("\n", encoding="utf-8")
    outside = tmp_path.parent / "outside.py"
    outside.write_text("\n", encoding="utf-8")
    text = (
        "`scripts/valid.py`\n"
        "https://example.invalid/scripts/remote.py\n"
        "`scripts/../../outside.py`\n"
    )

    errors, count = subject._check_script_references(tmp_path, Path("guide.md"), text)

    assert count == 2
    assert len(errors) == 1
    assert "script reference escapes" in errors[0]


def test_script_reference_roots_do_not_accept_arbitrary_suffixes(tmp_path: Path) -> None:
    (tmp_path / "scripts").mkdir()
    data_script = tmp_path / "data_sources" / "scripts" / "tool.py"
    data_script.parent.mkdir(parents=True)
    data_script.write_text("\n", encoding="utf-8")
    text = (
        "`data_sources/scripts/tool.py`\n"
        "`/absolute/repository/scripts/not-local.py`\n"
        "`other/scripts/not-local.py`\n"
        "https:scripts/not-local.py\n"
    )

    errors, count = subject._check_script_references(tmp_path, Path("guide.md"), text)

    assert errors == []
    assert count == 1


def test_script_reference_cannot_climb_out_of_script_root(tmp_path: Path) -> None:
    (tmp_path / "scripts").mkdir()
    (tmp_path / "outside.py").write_text("\n", encoding="utf-8")

    errors, count = subject._check_script_references(
        tmp_path,
        Path("guide.md"),
        "`scripts/../outside.py`\n",
    )

    assert count == 1
    assert len(errors) == 1
    assert "script reference escapes script root" in errors[0]


def test_markdown_anchor_check_supports_setext_and_ignores_fenced_headings(
    tmp_path: Path,
) -> None:
    target = tmp_path / "target.md"
    target.write_text(
        "Setext heading\n==============\n\n   # Indented heading\n\n"
        "```md\n# Fenced heading\n```\n\n<!--\n# Phantom\n-->\n",
        encoding="utf-8",
    )
    source = tmp_path / "guide.md"
    text = (
        "[setext](target.md#setext-heading)\n"
        "[indented](target.md#indented-heading)\n"
        "[fenced](target.md#fenced-heading)\n"
        "[comment](target.md#phantom)\n"
    )
    source.write_text(text, encoding="utf-8")

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert count == 4
    assert len(errors) == 2
    assert any("fenced-heading: fragment does not exist" in error for error in errors)
    assert any("phantom: fragment does not exist" in error for error in errors)


def test_multiline_setext_heading_uses_the_whole_paragraph(tmp_path: Path) -> None:
    target = tmp_path / "target.md"
    target.write_text("Multi\nline\n====\n", encoding="utf-8")
    text = "[whole](target.md#multi-line)\n[tail](target.md#line)\n"

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert count == 2
    assert len(errors) == 1
    assert "#line: fragment does not exist" in errors[0]


def test_markdown_link_check_handles_images_parentheses_and_references(
    tmp_path: Path,
) -> None:
    image = tmp_path / "images" / "a(b).png"
    image.parent.mkdir()
    image.write_bytes(b"fixture")
    reference = tmp_path / "reference.md"
    reference.write_text("# Reference\n", encoding="utf-8")
    text = (
        "![image](images/a(b).png)\n"
        "[reference][current]\n\n"
        "[current]: reference.md\n"
    )

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert errors == []
    assert count == 2


def test_markdown_link_check_handles_deep_balanced_parentheses(tmp_path: Path) -> None:
    target = tmp_path / "target_((v1)).md"
    target.write_text("# Deep\n", encoding="utf-8")
    text = "[deep](target_((v1)).md)\n"

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert errors == []
    assert count == 1


def test_markdown_link_check_unescapes_punctuation_in_destination(tmp_path: Path) -> None:
    target = tmp_path / "a(b).md"
    target.write_text("# Escaped\n", encoding="utf-8")

    errors, count = subject._check_links(
        tmp_path,
        Path("guide.md"),
        "[escaped](a\\(b\\).md)\n",
    )

    assert errors == []
    assert count == 1


@pytest.mark.parametrize(
    "reference",
    (
        "[missing][undefined]",
        "![missing][undefined]",
        "[undefined][]",
        "![undefined][]",
    ),
)
def test_undefined_reference_links_are_rejected(tmp_path: Path, reference: str) -> None:
    errors, count = subject._check_links(
        tmp_path,
        Path("guide.md"),
        f"{reference}\n",
    )

    assert count == 0
    assert len(errors) == 1
    assert "undefined reference link: undefined" in errors[0]


@pytest.mark.parametrize("reference", ("[undefined]", "![undefined]"))
def test_undefined_shortcut_reference_links_are_rejected(
    tmp_path: Path,
    reference: str,
) -> None:
    errors, count = subject._check_links(tmp_path, Path("guide.md"), f"{reference}\n")

    assert count == 0
    assert len(errors) == 1
    assert "undefined reference link: undefined" in errors[0]


def test_tensor_shape_brackets_are_not_shortcut_links(tmp_path: Path) -> None:
    errors, count = subject._check_links(
        tmp_path,
        Path("guide.md"),
        "Tensor shapes are [1,300,6] and [1,768,768,3].\n",
    )

    assert errors == []
    assert count == 0


def test_duplicate_reference_definitions_are_rejected(tmp_path: Path) -> None:
    existing = tmp_path / "existing.md"
    existing.write_text("# Existing\n", encoding="utf-8")
    text = (
        "[duplicate][]\n\n"
        "[duplicate]: missing.md\n"
        "[duplicate]: existing.md\n"
    )

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert count == 1
    assert any("duplicate reference definition: duplicate" in error for error in errors)
    assert any("missing.md: path does not exist" in error for error in errors)


@pytest.mark.parametrize("reference", ("[foo.bar]", "![foo.bar]"))
def test_punctuated_shortcut_reference_uses_its_definition(
    tmp_path: Path,
    reference: str,
) -> None:
    text = f"{reference}\n\n[foo.bar]: missing.md\n"

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert count == 1
    assert len(errors) == 1
    assert "missing.md: path does not exist" in errors[0]


def test_inline_code_and_task_box_are_not_shortcut_links(tmp_path: Path) -> None:
    errors, count = subject._check_links(
        tmp_path,
        Path("guide.md"),
        "`[inline]`\n- [x] complete\n",
    )

    assert errors == []
    assert count == 0


def test_fence_with_trailing_text_does_not_close_code_block(tmp_path: Path) -> None:
    target = tmp_path / "target.md"
    target.write_text(
        "```md\n``` still code\n# Phantom\n```\n\n# Real\n",
        encoding="utf-8",
    )
    text = "[phantom](target.md#phantom)\n[real](target.md#real)\n"

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert count == 2
    assert len(errors) == 1
    assert "phantom: fragment does not exist" in errors[0]


@pytest.mark.parametrize("selector", ("unit/extra", "UNIT", "unit.typo", "--help"))
def test_invalid_current_runner_shell_tokens_are_rejected(
    tmp_path: Path,
    selector: str,
) -> None:
    (tmp_path / "scripts").mkdir()
    runner = tmp_path / subject.RUNNER
    runner.write_text(
        """#!/usr/bin/env bash
case "${1:-validate}" in
  validate) ;;
  unit) ;;
  *) echo "Usage: $0 {validate|unit}" >&2; exit 2 ;;
esac
""",
        encoding="utf-8",
    )
    guide = tmp_path / "guide.md"
    guide.write_text(
        "`scripts/run_walksafe_test_layers_current.sh validate`\n"
        "`scripts/run_walksafe_test_layers_current.sh unit`\n"
        f'"scripts/run_walksafe_test_layers_current.sh" {selector}\n',
        encoding="utf-8",
    )

    result = subject.check_active_docs(tmp_path, (Path("guide.md"),))

    assert any(f"{selector}: documented selector is missing" in error for error in result.errors)


def test_quoted_current_runner_selector_is_normalized(tmp_path: Path) -> None:
    errors, selectors = subject._runner_commands(
        Path("guide.md"),
        'scripts/run_walksafe_test_layers_current.sh "validate"\n',
    )

    assert errors == []
    assert selectors == {"validate"}


@pytest.mark.parametrize(
    "command",
    (
        "scripts/run_walksafe_test_layers_current.sh || true",
        "scripts/run_walksafe_test_layers_current.sh >out.log",
        "scripts/run_walksafe_test_layers_current.sh 2>out.log",
        "scripts/run_walksafe_test_layers_current.sh 2>&1",
        "scripts/run_walksafe_test_layers_current.sh 0<&3",
        "scripts/run_walksafe_test_layers_current.sh 2>|out.log",
    ),
)
def test_current_runner_shell_boundaries_keep_default_selector(
    command: str,
) -> None:
    errors, selectors = subject._runner_commands(Path("guide.md"), command)

    assert errors == []
    assert selectors == {"all"}


def test_spaced_numeric_argument_is_not_treated_as_file_descriptor() -> None:
    errors, selectors = subject._runner_commands(
        Path("guide.md"),
        "scripts/run_walksafe_test_layers_current.sh 2 >out.log",
    )

    assert errors == []
    assert selectors == {"2"}


@pytest.mark.parametrize(
    "command",
    (
        "scripts/run_walksafe_test_layers_2026071?.sh validate",
        'r=scripts/run_walksafe_test_layers_20260711; "$r.sh" validate',
        "scripts/run_walksafe_test_layers_2026{0711,xxxx}.sh validate",
        "$(printf %s%s scripts/run_walksafe_test_layers_20260711 .sh) validate",
    ),
)
def test_dynamic_runner_family_commands_are_rejected(command: str) -> None:
    errors, selectors = subject._runner_commands(Path("guide.md"), command)

    assert selectors == set()
    assert len(errors) == 1
    assert "runner command is forbidden" in errors[0]


@pytest.mark.parametrize(
    "command",
    (
        "FOO=scripts/run_walksafe_test_layers_current.sh validate",
        "/tmp/run_walksafe_test_layers_current.sh unit",
    ),
)
def test_current_runner_requires_canonical_repository_path(command: str) -> None:
    errors, selectors = subject._runner_commands(Path("guide.md"), command)

    assert selectors == set()
    assert len(errors) == 1
    assert "canonical repository path" in errors[0]


def test_malformed_current_runner_path_suffix_is_rejected(tmp_path: Path) -> None:
    errors, selectors = subject._runner_commands(
        Path("guide.md"),
        "scripts/run_walksafe_test_layers_current.sh/extra unit\n",
    )

    assert selectors == set()
    assert len(errors) == 1
    assert "malformed runner path suffix '/extra'" in errors[0]


def test_malformed_url_is_reported_without_crashing(tmp_path: Path) -> None:
    text = "[bad](http://[::1)\n"

    errors, count = subject._check_links(tmp_path, Path("guide.md"), text)

    assert count == 0
    assert len(errors) == 1
    assert "malformed link" in errors[0]
