#!/usr/bin/env python3
"""Check the FP-007/FP-009 Android product and Legacy Web boundary."""

from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path
import re
import sys


REQUIRED_PATHS = (
    "README.md",
    "apps/android/README.md",
    "apps/web/README.md",
    "apps/web/package.json",
    "apps/web/proxy.ts",
    "apps/web/legacy-runtime-boundary.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "apps/web/app/api/_backend.ts",
    "apps/web/app/api/_gateway-auth.ts",
    "apps/web/app/api/_request-body.ts",
    "apps/web/app/api/field-session/route.ts",
    "apps/web/app/api/navigation/walking/route.ts",
    "apps/web/app/api/navigation/destinations/search/route.ts",
    "apps/web/app/api/reports/v2/route.ts",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
    ".github/workflows/quality.yml",
    "scripts/run_walksafe_remote_field_stack_20260711.sh",
    "scripts/run_walksafe_web_single_instance_20260713.py",
    "scripts/build_walksafe_web_release_20260711.sh",
    "scripts/build_walksafe_full_rc_20260713.py",
    "scripts/check_walksafe_release_evidence_20260711.py",
    "scripts/run_walksafe_product_quality_20260713.py",
    "scripts/validate_walksafe_full_rc_20260713.py",
    "scripts/run_cloudflare_field_test_services_20260711.sh",
    "deploy/README.md",
    "deploy/systemd/walksafe-web.service",
    "deploy/nginx/walksafe-web.conf.example",
    "deploy/config/walksafe-web.env.example",
)

EXPECTED_DEV_COMMAND = (
    "python3 ../../scripts/run_walksafe_web_single_instance_20260713.py -- "
    "./node_modules/.bin/next dev --hostname 127.0.0.1 --port 3000"
)
EXPECTED_START_COMMAND = (
    "WALKSAFE_WEB_START_MODE=production "
    "python3 ../../scripts/run_walksafe_web_single_instance_20260713.py -- "
    "./node_modules/.bin/next start --hostname 127.0.0.1 --port 3000"
)
EXPECTED_ROUTE_POLICY = """export const TRANSITIONAL_ANDROID_API_PATHS = [
  \"/api/field-session\",
  \"/api/navigation/walking\",
  \"/api/navigation/destinations/search\",
  \"/api/reports/v2\"
] as const;

const transitionalAndroidApiPaths = new Set<string>(TRANSITIONAL_ANDROID_API_PATHS);

export function isTransitionalAndroidApiPath(pathname: string): boolean {
  return transitionalAndroidApiPaths.has(pathname);
}
"""
EXPECTED_PROXY = """import type { NextRequest } from \"next/server\";
import { isTransitionalAndroidApiPath } from \"./legacy-runtime-boundary\";

export function proxy(request: NextRequest): Response | undefined {
  if (isTransitionalAndroidApiPath(request.nextUrl.pathname)) return undefined;

  return new Response(\"Legacy Web runtime is closed.\\n\", {
    status: 410,
    headers: {
      \"cache-control\": \"no-store\",
      \"content-type\": \"text/plain; charset=utf-8\",
      \"x-walksafe-product-boundary\": \"legacy-web-closed\"
    }
  });
}

export const config = {
  matcher: \"/:path*\"
};
"""
EXPECTED_BFF_PATHS = (
    "/api/field-session",
    "/api/navigation/walking",
    "/api/navigation/destinations/search",
    "/api/reports/v2",
)
EXPECTED_GATE_IDS = (
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
)


class BoundaryError(RuntimeError):
    """Raised when a current path can promote or expose Legacy Web."""


def _read(root: Path, relative: str) -> str:
    path = root / relative
    if path.is_symlink() or not path.is_file():
        raise BoundaryError(f"required regular file is missing: {relative}")
    return path.read_text(encoding="utf-8")


def _require(text: str, values: tuple[str, ...], context: str) -> None:
    missing = [value for value in values if value not in text]
    if missing:
        raise BoundaryError(f"{context} is missing: {', '.join(missing)}")


def _first_executable_line(text: str, pattern: str) -> int | None:
    matcher = re.compile(pattern)
    for index, line in enumerate(text.splitlines()):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if matcher.search(stripped):
            return index
    return None


def _executable_lines(text: str) -> list[str]:
    return [
        stripped
        for line in text.splitlines()
        if (stripped := line.strip()) and not stripped.startswith("#")
    ]


def _require_shell_fail_closed(
    text: str,
    *,
    context: str,
    expected_guard: tuple[str, ...],
    side_effects: tuple[str, ...],
) -> None:
    if not text.startswith("#!/bin/bash -p\n"):
        raise BoundaryError(f"{context} must use privileged Bash startup isolation")
    if tuple(_executable_lines(text)[: len(expected_guard)]) != expected_guard:
        raise BoundaryError(f"{context} must begin with the exact fail-closed guard")
    exit_line = _first_executable_line(text, r"^exit[ \t]+78(?:[ \t]*(?:#.*)?)?$")
    if exit_line is None:
        raise BoundaryError(f"{context} has no executable exit 78 guard")
    for marker in side_effects:
        marker_line = _first_executable_line(text, marker)
        if marker_line is not None and exit_line > marker_line:
            raise BoundaryError(f"{context} guard runs after side-effect marker: {marker}")


def _require_python_main_fail_closed(text: str, *, context: str) -> None:
    tree = ast.parse(text)
    main = next(
        (node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "main"),
        None,
    )
    if main is None or len(main.body) < 2:
        raise BoundaryError(f"{context} has no fail-closed main")
    first, second = main.body[:2]
    if not (
        isinstance(first, ast.Expr)
        and isinstance(first.value, ast.Call)
        and isinstance(first.value.func, ast.Name)
        and first.value.func.id == "print"
        and isinstance(second, ast.Return)
        and isinstance(second.value, ast.Constant)
        and second.value.value == 78
    ):
        raise BoundaryError(f"{context} must print and return 78 before historical CLI work")


def _require_exact_function(
    text: str,
    *,
    function_name: str,
    expected_source: str,
    context: str,
) -> None:
    tree = ast.parse(text)
    function = next(
        (
            node
            for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name == function_name
        ),
        None,
    )
    expected = ast.parse(expected_source).body[0]
    if function is None or ast.dump(function, include_attributes=False) != ast.dump(
        expected, include_attributes=False
    ):
        raise BoundaryError(f"{context} differs from the exact fail-closed contract")


def _require_exact_function_prefix(
    text: str,
    *,
    function_name: str,
    expected_source: str,
    context: str,
) -> None:
    tree = ast.parse(text)
    function = next(
        (
            node
            for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name == function_name
        ),
        None,
    )
    expected = ast.parse(expected_source).body[0]
    if (
        function is None
        or len(function.body) < len(expected.body)
        or any(
            ast.dump(actual, include_attributes=False)
            != ast.dump(wanted, include_attributes=False)
            for actual, wanted in zip(function.body, expected.body)
        )
    ):
        raise BoundaryError(f"{context} lacks the exact fail-closed function prefix")


def _require_early_cli_guard(text: str, *, expected_source: str, context: str) -> None:
    tree = ast.parse(text)
    actual = next(
        (
            node
            for node in tree.body
            if isinstance(node, ast.If)
            and isinstance(node.test, ast.Compare)
            and isinstance(node.test.left, ast.Name)
            and node.test.left.id == "__name__"
        ),
        None,
    )
    expected = ast.parse(expected_source).body[0]
    safe_prefix = (
        len(tree.body) >= 4
        and isinstance(tree.body[0], ast.Expr)
        and isinstance(tree.body[0].value, ast.Constant)
        and isinstance(tree.body[0].value.value, str)
        and isinstance(tree.body[1], ast.ImportFrom)
        and tree.body[1].module == "__future__"
        and isinstance(tree.body[2], ast.Import)
        and [alias.name for alias in tree.body[2].names] == ["sys"]
        and actual is tree.body[3]
    )
    if (
        not safe_prefix
        or actual is None
        or ast.dump(actual, include_attributes=False)
        != ast.dump(expected, include_attributes=False)
    ):
        raise BoundaryError(f"{context} lacks the exact pre-import exit 78 guard")


def _active_configuration_lines(text: str) -> list[str]:
    return [
        stripped
        for line in text.splitlines()
        if (stripped := line.strip()) and not stripped.startswith("#")
    ]


def check_boundary(root: Path) -> None:
    root = root.resolve()
    for relative in REQUIRED_PATHS:
        _read(root, relative)

    readme = _read(root, "README.md")
    _require(
        readme,
        (
            "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "일반 사용자용 Android 앱",
            "별도 Android 관리자 앱",
            "LEGACY_REFERENCE_ONLY",
            "전환형 Next BFF/API route",
            "NOT_ELIGIBLE",
        ),
        "root README product boundary",
    )
    for stale in ("주 사용자 앱은 **Next.js", "Web/PWA 주 앱", "Android 보조 연구"):
        if stale in readme:
            raise BoundaryError(f"root README retains a stale product claim: {stale}")

    android_readme = _read(root, "apps/android/README.md")
    _require(
        android_readme,
        ("WalkSafe Android 사용자 앱", "별도 Android 관리자 앱", "NOT_ELIGIBLE"),
        "Android README product boundary",
    )
    for stale in ("주 사용자 앱은 Web/PWA", "보조 연구 APK"):
        if stale in android_readme:
            raise BoundaryError(f"Android README retains a stale product claim: {stale}")

    web_readme = _read(root, "apps/web/README.md")
    _require(
        web_readme,
        (
            "LEGACY_REFERENCE_ONLY",
            "전환형 Android BFF/API route",
            "/api/field-session",
            "/api/navigation/walking",
            "/api/navigation/destinations/search",
            "/api/reports/v2",
            "외부 주소에 공개하거나 사용자 시험·정식 배포·출시 후보로 사용하지 않습니다",
            "410 Gone",
            "loopback",
        ),
        "Legacy Web README boundary",
    )
    if "현재 WalkSafe의 주 사용자 앱" in web_readme:
        raise BoundaryError("Web README still declares Web as the current user product")

    package = json.loads(_read(root, "apps/web/package.json"))
    scripts = package.get("scripts")
    if not isinstance(scripts, dict):
        raise BoundaryError("Legacy Web package scripts are missing")
    if scripts.get("dev") != EXPECTED_DEV_COMMAND:
        raise BoundaryError("Legacy BFF dev command differs from the exact loopback contract")
    if scripts.get("start") != EXPECTED_START_COMMAND:
        raise BoundaryError("Legacy BFF start command differs from the exact loopback contract")
    if scripts.get("build") != "next build":
        raise BoundaryError("local Legacy/BFF regression build contract changed")

    route_policy = _read(root, "apps/web/legacy-runtime-boundary.ts")
    if route_policy != EXPECTED_ROUTE_POLICY:
        raise BoundaryError("transitional Android BFF allowlist implementation is not exact")
    proxy = _read(root, "apps/web/proxy.ts")
    if proxy != EXPECTED_PROXY:
        raise BoundaryError("Legacy Web runtime request boundary differs from the exact contract")
    web_runner = _read(root, "scripts/run_walksafe_web_single_instance_20260713.py")
    _require_exact_function(
        web_runner,
        function_name="require_loopback_web_command",
        expected_source="""def require_loopback_web_command(command: list[str]) -> None:
    if tuple(command) not in LOOPBACK_NEXT_COMMANDS:
        fail(\"Legacy Web/BFF runtime commands must use the exact loopback Next dev/start contract\")
""",
        context="loopback transitional BFF runner",
    )

    android_source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((root / "apps/android/app/src/main").rglob("*.kt"))
        if path.is_file() and not path.is_symlink()
    )
    android_api_paths = set(re.findall(r'"(/api/[^"$]+)"', android_source))
    if android_api_paths != set(EXPECTED_BFF_PATHS):
        raise BoundaryError(
            "Android transitional BFF dependency set differs: "
            + ", ".join(sorted(android_api_paths))
        )

    workflow = _read(root, ".github/workflows/quality.yml")
    _require(
        workflow,
        (
            "Install Legacy Web regression dependencies",
            "npm --prefix apps/web ci",
            "npm --prefix apps/web run lint",
            "npm --prefix apps/web run typecheck",
            "npm --prefix apps/web run build",
            "scripts/run_walksafe_test_layers_20260711.sh all",
        ),
        "quality workflow Legacy Web regression contract",
    )
    forbidden_workflow_values = (
        "build_walksafe_web_release_20260711.sh",
        "--profile web-release",
        "web-build-manifest.json",
        "web-standalone-",
        "apps/web/.next",
        "WALKSAFE_RUN_BROWSER_E2E",
        "artifacts/ci/",
        "web-build-id.txt",
    )
    found = [value for value in forbidden_workflow_values if value in workflow]
    if found:
        raise BoundaryError(
            "quality workflow still creates or uploads a Web release artifact: "
            + ", ".join(found)
        )

    shell_closures = {
        "scripts/run_walksafe_remote_field_stack_20260711.sh": ((
            "set -euo pipefail",
            'echo "BLOCKED: Legacy Web/PWA external runtime is LEGACY_REFERENCE_ONLY under FP-009." >&2',
            'echo "Use the Android product workflow; this launcher must not create a public tunnel." >&2',
            "exit 78",
        ), (
            r"^CLOUDFLARED_BIN=",
            r"^mkdir[ \t]+-p(?:[ \t]|$)",
            r"tunnel[ \t]+--no-autoupdate",
        )),
        "scripts/run_cloudflare_field_test_services_20260711.sh": ((
            "set -euo pipefail",
            'echo "BLOCKED: Legacy Web/PWA field services are LEGACY_REFERENCE_ONLY under FP-009." >&2',
            'echo "This launcher must not build or start the historical Web field stack." >&2',
            "exit 78",
        ), (
            r"^REPO_ROOT=",
            r"^mkdir[ \t]+-p(?:[ \t]|$)",
            r"docker[ \t]+compose",
            r"npm[ \t]+run[ \t]+build",
        )),
        "scripts/build_walksafe_web_release_20260711.sh": ((
            "set -euo pipefail",
            'echo "BLOCKED: Legacy Web/PWA release packaging is LEGACY_REFERENCE_ONLY under FP-009." >&2',
            'echo "Only local BFF/regression builds are allowed until the Android API gateway is extracted." >&2',
            "exit 78",
        ), (
            r"^SCRIPT_DIR=",
            r"^OUTPUT=",
            r"npm[ \t]+run[ \t]+build",
            r"tar[ \t]",
        )),
    }
    for relative, (guard, markers) in shell_closures.items():
        _require_shell_fail_closed(
            _read(root, relative),
            context=f"historical shell path {relative}",
            expected_guard=guard,
            side_effects=markers,
        )

    for relative in (
        "scripts/build_walksafe_full_rc_20260713.py",
        "scripts/validate_walksafe_full_rc_20260713.py",
    ):
        _require_python_main_fail_closed(
            _read(root, relative), context=f"historical Python CLI {relative}"
        )
    startup_guard = """if __name__ == \"__main__\":
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, \"safe_path\", False)),
        sys.flags.dont_write_bytecode,
    )
    if any(value != 1 for value in _startup_flags) or \"site\" in sys.modules:
        raise SystemExit(CLI_ISOLATION_MESSAGE)
    print(CLI_BLOCKED_MESSAGE, file=sys.stderr)
    raise SystemExit(78)
"""
    for relative in (
        "scripts/build_walksafe_full_rc_20260713.py",
        "scripts/validate_walksafe_full_rc_20260713.py",
    ):
        source = _read(root, relative)
        isolation_match = re.search(r'raise SystemExit\(\n\s*("[^"]+")\n\s*\)', source)
        blocked_match = re.search(r'print\(\n\s*("BLOCKED:[^"]+")', source)
        if isolation_match is None or blocked_match is None:
            raise BoundaryError(f"historical Python CLI {relative} guard messages are missing")
        expected = startup_guard.replace("CLI_ISOLATION_MESSAGE", isolation_match.group(1)).replace(
            "CLI_BLOCKED_MESSAGE", blocked_match.group(1)
        )
        _require_early_cli_guard(
            source,
            expected_source=expected,
            context=f"historical Python CLI {relative}",
        )

    evidence_gate = _read(root, "scripts/check_walksafe_release_evidence_20260711.py")
    _require_early_cli_guard(
        evidence_gate,
        expected_source="""if __name__ == \"__main__\":
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, \"safe_path\", False)),
        sys.flags.dont_write_bytecode,
    )
    if any(value != 1 for value in _startup_flags) or \"site\" in sys.modules:
        raise SystemExit(
            \"WalkSafe release evidence CLI requires Python -I -S -B before any release code runs\"
    )
    _arguments = sys.argv[1:]
    _requested_profiles = []
    for _index, _argument in enumerate(_arguments):
        if _argument == \"--profile\" and _index + 1 < len(_arguments):
            _requested_profiles.append(_arguments[_index + 1])
        if _argument.startswith(\"--profile=\"):
            _requested_profiles.append(_argument.partition(\"=\")[2])
    if not _requested_profiles:
        _requested_profiles.append(\"web-release\")
    if (
        \"--help\" not in _arguments
        and \"-h\" not in _arguments
        and any(profile in {\"web-release\", \"full\"} for profile in _requested_profiles)
    ):
        print(
            \"BLOCKED: Web/full release evidence profiles are LEGACY_REFERENCE_ONLY under FP-009.\",
            file=sys.stderr,
        )
        raise SystemExit(78)
""",
        context="historical Web/full release evidence CLI",
    )
    _require_exact_function_prefix(
        evidence_gate,
        function_name="main",
        expected_source="""def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.profile in {\"web-release\", \"full\"}:
        print(
            \"BLOCKED: Web/full release evidence profiles are LEGACY_REFERENCE_ONLY under FP-009.\",
            file=sys.stderr,
        )
        return 78
""",
        context="historical Web/full release evidence imported entrypoint",
    )
    quality_runner = _read(root, "scripts/run_walksafe_product_quality_20260713.py")
    _require_exact_function_prefix(
        quality_runner,
        function_name="main",
        expected_source="""def main() -> int:
    try:
        require_isolated_python(\"WalkSafe product quality CLI\")
    except ReleaseIntegrityError as exc:
        print(f\"WalkSafe product quality FAIL: {exc}\", file=sys.stderr)
        return 2
    args = parse_args()
    if args.product == \"web\":
        print(
            \"BLOCKED: Legacy Web/PWA product quality execution is LEGACY_REFERENCE_ONLY under FP-009.\",
            file=sys.stderr,
        )
        return 78
""",
        context="historical Web product quality branch",
    )

    deploy_readme = _read(root, "deploy/README.md")
    _require(
        deploy_readme,
        (
            "LEGACY_REFERENCE_ONLY",
            "설치·enable·start·nginx reload 절차에 사용하면 안 됩니다",
            "전환형 BFF",
        ),
        "deployment classification",
    )
    for relative in (
        "deploy/systemd/walksafe-web.service",
        "deploy/nginx/walksafe-web.conf.example",
        "deploy/config/walksafe-web.env.example",
    ):
        first_lines = "\n".join(_read(root, relative).splitlines()[:4])
        if "LEGACY_REFERENCE_ONLY" not in first_lines or "DO NOT INSTALL" not in first_lines:
            raise BoundaryError(f"historical Web deployment input is not classified: {relative}")
        active_lines = _active_configuration_lines(_read(root, relative))
        if active_lines:
            raise BoundaryError(
                f"historical Web deployment input still has active configuration: {relative}"
            )

    boundary = json.loads(_read(root, "configs/walksafe_product_boundary_20260722.json"))
    legacy = boundary.get("products", {}).get("legacy_web", {})
    routes = legacy.get("transitional_android_api_routes", {})
    release = boundary.get("release_control", {})
    gates = release.get("remaining_gates")
    if (
        boundary.get("schema_version") != "1.2.0"
        or boundary.get("version") != "1.2.0"
        or legacy.get("product_role") != "LEGACY_REFERENCE_ONLY"
        or legacy.get("external_user_runtime_allowed") is not False
        or legacy.get("formal_release_component_allowed") is not False
        or legacy.get("remaining_executable_historical_inputs") != []
        or legacy.get("technical_closure_status")
        != "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION"
        or routes.get("extraction_status") != "NOT_COMPLETED"
        or tuple(routes.get("runtime_allowlist", ())) != EXPECTED_BFF_PATHS
        or release.get("release_eligibility") != "NOT_ELIGIBLE"
        or release.get("public_distribution_allowed") is not False
        or not isinstance(gates, list)
        or tuple(gate.get("gate_id") for gate in gates) != EXPECTED_GATE_IDS
        or any(
            gate.get("execution_status") != "NOT_RUN" or gate.get("waived") is not False
            for gate in gates
        )
    ):
        raise BoundaryError("product boundary does not preserve the Phase D closure and release gates")

    historical_tools = (
        "scripts/build_walksafe_web_release_20260711.sh",
        "scripts/build_walksafe_full_rc_20260713.py",
        "scripts/check_walksafe_release_evidence_20260711.py",
        "scripts/validate_walksafe_full_rc_20260713.py",
        "scripts/run_cloudflare_field_test_services_20260711.sh",
    )
    for relative in historical_tools:
        if relative not in web_readme:
            name = Path(relative).name
            if name not in web_readme:
                raise BoundaryError(f"historical release tool is not classified in Web README: {name}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    try:
        check_boundary(args.root)
    except (BoundaryError, OSError, UnicodeError, ValueError) as exc:
        print(f"WalkSafe Legacy Web boundary check: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "WalkSafe Legacy Web boundary check: PASS "
        "(Legacy UI 410, release/deploy launchers blocked, transitional Android BFF preserved)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
