#!/usr/bin/env python3
"""Check the Phase E Android Gateway and closed Legacy Web runtime boundary."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any


PUBLIC_ROUTES = {
    "/api/field-session": {"get", "post", "delete"},
    "/api/navigation/walking": {"post"},
    "/api/navigation/destinations/search": {"get"},
    "/api/reports/v2": {"post"},
    "/api/field-walk": {"get", "post"},
}
PUBLIC_NON_API_ROUTES = ("/privacy/rights",)

REMOVED_NEXT_ROUTES = (
    "apps/web/app/api/field-session/route.ts",
    "apps/web/app/api/navigation/walking/route.ts",
    "apps/web/app/api/navigation/destinations/search/route.ts",
    "apps/web/app/api/reports/v2/route.ts",
)

REQUIRED_PATHS = (
    ".github/workflows/quality.yml",
    "README.md",
    "apps/README.md",
    "apps/android/README.md",
    "apps/android/app/build.gradle.kts",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
    "apps/android-gateway/README.md",
    "apps/android-gateway/openapi.json",
    "apps/android-gateway/package-lock.json",
    "apps/android-gateway/package.json",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/config.ts",
    "apps/android-gateway/src/node-adapter.ts",
    "apps/android-gateway/src/request-body.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/server.ts",
    "apps/android-gateway/tsconfig.json",
    "apps/web/README.md",
    "apps/web/legacy-runtime-boundary.ts",
    "apps/web/package.json",
    "apps/web/proxy.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "deploy/README.md",
    "deploy/config/walksafe-android-gateway.env.example",
    "deploy/nginx/walksafe-android-gateway.conf.example",
    "deploy/systemd/walksafe-android-gateway.service",
    "scripts/check_frontend_policy_suite.sh",
    "scripts/run_walksafe_test_layers_20260711.sh",
)

EXPECTED_LEGACY_POLICY = """export const TRANSITIONAL_ANDROID_API_PATHS = [] as const;

export function isTransitionalAndroidApiPath(_pathname: string): boolean {
  return false;
}
"""

EXPECTED_LEGACY_PROXY = """import type { NextRequest } from \"next/server\";

export function proxy(_request: NextRequest): Response {
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

EXPECTED_GATE_IDS = (
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
)


class BoundaryError(RuntimeError):
    """Raised when the Android Gateway or Legacy Web boundary is unsafe."""


def _read(root: Path, relative: str) -> str:
    path = root / relative
    if path.is_symlink() or not path.is_file():
        raise BoundaryError(f"required regular file is missing: {relative}")
    return path.read_text(encoding="utf-8")


def _strict_json(root: Path, relative: str) -> dict[str, Any]:
    def no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise BoundaryError(f"duplicate JSON key in {relative}: {key}")
            value[key] = item
        return value

    decoded = json.loads(_read(root, relative), object_pairs_hook=no_duplicates)
    if not isinstance(decoded, dict):
        raise BoundaryError(f"JSON root must be an object: {relative}")
    return decoded


def _require(text: str, values: tuple[str, ...], context: str) -> None:
    missing = [value for value in values if value not in text]
    if missing:
        raise BoundaryError(f"{context} is missing: {', '.join(missing)}")


def _openapi_methods(document: dict[str, Any]) -> dict[str, set[str]]:
    paths = document.get("paths")
    if not isinstance(paths, dict):
        raise BoundaryError("Gateway OpenAPI paths are missing")
    methods: dict[str, set[str]] = {}
    for path, operation in paths.items():
        if not isinstance(path, str) or not isinstance(operation, dict):
            raise BoundaryError("Gateway OpenAPI path entry is invalid")
        methods[path] = {
            method.lower()
            for method in operation
            if method.lower() in {"get", "post", "put", "patch", "delete", "options", "head"}
        }
    return methods


def check_boundary(root: Path) -> None:
    root = root.resolve()
    for relative in REQUIRED_PATHS:
        _read(root, relative)
    restored = [relative for relative in REMOVED_NEXT_ROUTES if (root / relative).exists()]
    if restored:
        raise BoundaryError(f"extracted Next route was restored: {', '.join(restored)}")

    package = _strict_json(root, "apps/android-gateway/package.json")
    if package.get("name") != "walksafe-android-gateway" or package.get("type") != "module":
        raise BoundaryError("Gateway package identity or ESM contract changed")
    scripts = package.get("scripts")
    if not isinstance(scripts, dict):
        raise BoundaryError("Gateway package scripts are missing")
    for name in ("build", "clean", "start", "test", "typecheck"):
        if not isinstance(scripts.get(name), str) or not scripts[name]:
            raise BoundaryError(f"Gateway package script is missing: {name}")
    if scripts.get("build") != "npm run clean && tsc":
        raise BoundaryError("Gateway build must remove package-local dist before compiling")
    if "rmSync('dist', { recursive: true, force: true })" not in scripts.get("clean", ""):
        raise BoundaryError("Gateway clean script must target only package-local dist")
    if scripts.get("start") != "node dist/server.js":
        raise BoundaryError("Gateway start entrypoint must be node dist/server.js")
    dependency_names = set((package.get("dependencies") or {})) | set(
        (package.get("devDependencies") or {})
    )
    if {"next", "react", "react-dom"} & dependency_names:
        raise BoundaryError("Gateway must not depend on Next or React")

    openapi = _strict_json(root, "apps/android-gateway/openapi.json")
    if _openapi_methods(openapi) != PUBLIC_ROUTES:
        raise BoundaryError("Gateway OpenAPI does not expose the exact five-route contract")
    report_multipart = openapi["paths"]["/api/reports/v2"]["post"]["requestBody"]["content"]["multipart/form-data"]
    report_schema = report_multipart.get("schema", {})
    report_properties = report_schema.get("properties", {})
    if (
        report_schema.get("required") != ["metadata", "image"]
        or report_schema.get("additionalProperties") is not False
        or report_properties.get("metadata", {}).get("x-max-utf8-bytes") != 64 * 1024
        or report_properties.get("image", {}).get("contentMediaType") != "image/jpeg"
        or report_properties.get("image", {}).get("x-min-bytes") != 1
        or report_properties.get("image", {}).get("x-max-bytes") != 8 * 1024 * 1024
        or report_multipart.get("encoding", {}).get("image", {}).get("contentType") != "image/jpeg"
    ):
        raise BoundaryError("Gateway OpenAPI report multipart bounds changed")
    serialized_openapi = json.dumps(openapi, ensure_ascii=False).lower()
    for internal_name in (
        "x-walksafe-field-test-token",
        "x-walksafe-actor-assertion",
        "walksafe_gateway_session_secret",
    ):
        if internal_name in serialized_openapi:
            raise BoundaryError(f"Gateway OpenAPI exposes an internal credential: {internal_name}")

    config_source = _read(root, "apps/android-gateway/src/config.ts")
    _require(
        config_source,
        (
            '"http://127.0.0.1:8000"',
            '"127.0.0.1"',
            "DEFAULT_GATEWAY_PORT = 8081",
            "resolvePrivacyRightsRequestUrl",
        ),
        "Gateway loopback configuration",
    )
    if "0.0.0.0" in config_source or '"localhost"' in config_source:
        raise BoundaryError("Gateway configuration permits a non-exact bind or backend host")

    route_source = _read(root, "apps/android-gateway/src/routes.ts")
    actual_routes: dict[str, set[str]] = {}
    for path, methods_source in re.findall(r'\["(/api/[^\"]+)", \[([^\]]+)\]\]', route_source):
        actual_routes[path] = set(re.findall(r'"(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD)"', methods_source))
    expected_routes = {path: {method.upper() for method in methods} for path, methods in PUBLIC_ROUTES.items()}
    if actual_routes != expected_routes:
        raise BoundaryError("Gateway router does not expose the exact path/method allowlist")
    _require(
        route_source,
        (
            'readBoundedJsonBody(request, 4 * 1024)',
            'readBoundedTextBody(request, 16 * 1024)',
            "IMAGE_MULTIPART_LIMIT_BYTES",
            'const PRIVACY_RIGHTS_PATH = "/privacy/rights"',
            '"cache-control": "no-store"',
        ),
        "Gateway route safety contract",
    )
    auth_source = _read(root, "apps/android-gateway/src/auth.ts")
    _require(
        auth_source,
        (
            'const FIELD_COOKIE = "walksafe_field_session"',
            "12 * 60 * 60",
            '"HttpOnly"',
            '"SameSite=Strict"',
            'attributes.push("Secure")',
        ),
        "Gateway field-session cookie contract",
    )
    backend_source = _read(root, "apps/android-gateway/src/backend.ts")
    _require(
        backend_source,
        (
            '"x-walksafe-field-test-token"',
            '"x-walksafe-actor-assertion"',
            "IMAGE_MULTIPART_LIMIT_BYTES = 9 * 1024 * 1024",
            "REPORT_METADATA_LIMIT_BYTES = 64 * 1024",
            "REPORT_IMAGE_LIMIT_BYTES = 8 * 1024 * 1024",
            "countMultipartDelimiters(bytes, boundary) !== 3",
            'entries.length !== 2',
            '"gateway_upstream_auth_failed"',
            "status: 502",
        ),
        "Gateway protected backend contract",
    )
    combined_gateway_source = "\n".join(
        _read(root, relative)
        for relative in REQUIRED_PATHS
        if relative.startswith("apps/android-gateway/src/")
    )
    if re.search(r'from\s+["\'](?:next|react)', combined_gateway_source):
        raise BoundaryError("Gateway source imports Next or React")

    server_source = _read(root, "apps/android-gateway/server.ts")
    _require(
        server_source,
        ("cluster.isPrimary", "createGatewayServer", "resolveBindAddress"),
        "Gateway single-process server contract",
    )

    android_build = _read(root, "apps/android/app/build.gradle.kts")
    if '"http://127.0.0.1:8081"' not in android_build or "127.0.0.1:3000" in android_build:
        raise BoundaryError("Android debug Gateway origin is not the independent loopback service")
    android_sources = "\n".join(
        _read(root, relative)
        for relative in REQUIRED_PATHS
        if relative.startswith("apps/android/app/src/main/java/")
    )
    android_paths = set(re.findall(r'"(/api/[^"$]+)"', android_sources))
    android_paths.update(
        path
        for path, methods in actual_routes.items()
        if path == "/api/field-walk" and methods == {"GET", "POST"}
    )
    if android_paths != set(PUBLIC_ROUTES):
        raise BoundaryError(f"Android Gateway path set differs: {sorted(android_paths)}")
    if "127.0.0.1:3000" in android_sources or "127.0.0.1:8000" in android_sources:
        raise BoundaryError("Android retains a Next or direct Backend fallback")

    if _read(root, "apps/web/legacy-runtime-boundary.ts") != EXPECTED_LEGACY_POLICY:
        raise BoundaryError("Legacy Web runtime allowlist is not exactly empty")
    if _read(root, "apps/web/proxy.ts") != EXPECTED_LEGACY_PROXY:
        raise BoundaryError("Legacy Web proxy is not the exact all-request 410 boundary")

    boundary = _strict_json(root, "configs/walksafe_product_boundary_20260722.json")
    products = boundary.get("products") or {}
    gateway = products.get("android_api_gateway") or {}
    legacy = products.get("legacy_web") or {}
    transition = legacy.get("transitional_android_api_routes") or {}
    release = boundary.get("release_control") or {}
    gates = release.get("remaining_gates")
    if (
        boundary.get("schema_version") != "1.3.0"
        or boundary.get("version") != "1.3.0"
        or gateway.get("source_path") != "apps/android-gateway"
        or gateway.get("official_local_bind") != "127.0.0.1:8081"
        or gateway.get("backend_origin") != "http://127.0.0.1:8000"
        or gateway.get("public_routes") != list(PUBLIC_ROUTES)
        or gateway.get("public_non_api_routes") != list(PUBLIC_NON_API_ROUTES)
        or gateway.get("legacy_next_fallback") != "PROHIBITED"
        or gateway.get("deployment_status") != "NOT_RUN"
        or gateway.get("actual_device_connectivity_status") != "NOT_RUN"
        or legacy.get("product_role") != "LEGACY_REFERENCE_ONLY"
        or legacy.get("external_user_runtime_allowed") is not False
        or legacy.get("technical_closure_status") != "COMPLETE_WITHOUT_RUNTIME_ALLOWLIST_INTERNAL"
        or transition.get("extraction_status") != "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED"
        or transition.get("runtime_allowlist") != []
        or release.get("release_eligibility") != "NOT_ELIGIBLE"
        or release.get("public_distribution_allowed") is not False
        or not isinstance(gates, list)
        or tuple(gate.get("gate_id") for gate in gates) != EXPECTED_GATE_IDS
        or any(gate.get("execution_status") != "NOT_RUN" or gate.get("waived") is not False for gate in gates)
    ):
        raise BoundaryError("product boundary overstates Phase E, deployment, tests, gates, or release")

    workflow = _read(root, ".github/workflows/quality.yml")
    _require(
        workflow,
        (
            "apps/android-gateway/package-lock.json",
            "npm --prefix apps/android-gateway ci",
            "npm --prefix apps/android-gateway run typecheck",
            "npm --prefix apps/android-gateway test",
            "npm --prefix apps/web run build",
        ),
        "quality workflow Gateway contract",
    )
    if "build_walksafe_web_release_20260711.sh" in workflow or "apps/web/.next" in workflow:
        raise BoundaryError("quality workflow restores a Legacy Web release artifact")

    deploy_readme = _read(root, "deploy/README.md")
    _require(
        deploy_readme,
        ("DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED", "127.0.0.1:8081", "NOT_ELIGIBLE"),
        "Gateway deployment authority boundary",
    )
    env_example = _read(root, "deploy/config/walksafe-android-gateway.env.example")
    unit_example = _read(root, "deploy/systemd/walksafe-android-gateway.service")
    nginx_example = _read(root, "deploy/nginx/walksafe-android-gateway.conf.example")
    for relative, source in (
        ("gateway env", env_example),
        ("gateway systemd", unit_example),
        ("gateway nginx", nginx_example),
    ):
        if "DRAFT_DEPLOYMENT_EXAMPLE" not in source or "NOT_APPLIED" not in source:
            raise BoundaryError(f"{relative} example lacks its non-applied authority boundary")
    _require(
        env_example,
        ("WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL=https://",),
        "Gateway privacy rights configuration",
    )
    _require(unit_example, ("127.0.0.1", "dist/server.js", "IPAddressDeny=any"), "Gateway systemd example")
    for route in PUBLIC_ROUTES:
        if f"location = {route} {{" not in nginx_example:
            raise BoundaryError(f"Gateway nginx example is missing exact route: {route}")
    for route in PUBLIC_NON_API_ROUTES:
        if f"location = {route} {{" not in nginx_example:
            raise BoundaryError(f"Gateway nginx example is missing public non-API route: {route}")
    if nginx_example.count("proxy_pass http://127.0.0.1:8081;") != (
        len(PUBLIC_ROUTES) + len(PUBLIC_NON_API_ROUTES)
    ):
        raise BoundaryError("Gateway nginx example has a non-exact upstream count")
    if "proxy_pass http://127.0.0.1:8000" in nginx_example or "127.0.0.1:3000" in nginx_example:
        raise BoundaryError("Gateway nginx example exposes Backend or Next fallback")
    if "access_log off;" not in nginx_example or "$request_uri" in nginx_example:
        raise BoundaryError("Gateway nginx example must not log query-bearing request targets")
    if 'add_header Cache-Control "no-store" always;' not in nginx_example:
        raise BoundaryError("Gateway nginx example must mark ingress-generated responses no-store")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    try:
        check_boundary(args.root)
    except (BoundaryError, OSError, UnicodeError, ValueError, json.JSONDecodeError) as exc:
        print(f"WalkSafe Android Gateway boundary check: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "WalkSafe Android Gateway boundary check: PASS "
        "(5 APIs + privacy rights page, Android 8081, Legacy Web all-request 410, deploy/device NOT_RUN)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
