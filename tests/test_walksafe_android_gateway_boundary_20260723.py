from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHECKER_PATH = ROOT / "scripts/check_walksafe_android_gateway_boundary_20260723.py"
SPEC = importlib.util.spec_from_file_location("walksafe_android_gateway_boundary", CHECKER_PATH)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class WalkSafeAndroidGatewayBoundaryTest(unittest.TestCase):
    def _copy_contract(self, destination: Path) -> None:
        for relative in CHECKER.REQUIRED_PATHS:
            source = ROOT / relative
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    def test_current_repository_passes(self) -> None:
        CHECKER.check_boundary(ROOT)

    def test_removed_next_route_cannot_be_restored(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            route = root / CHECKER.REMOVED_NEXT_ROUTES[0]
            route.parent.mkdir(parents=True, exist_ok=True)
            route.write_text("export async function POST() {}\n", encoding="utf-8")
            with self.assertRaisesRegex(CHECKER.BoundaryError, "extracted Next route was restored"):
                CHECKER.check_boundary(root)

    def test_gateway_route_allowlist_cannot_expand(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            route = root / "apps/android-gateway/src/routes.ts"
            route.write_text(
                route.read_text(encoding="utf-8").replace(
                    '["/api/reports/v2", ["POST"]]\n]);',
                    '["/api/reports/v2", ["POST"]],\n  ["/api/admin-session", ["POST"]]\n]);',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "exact path/method allowlist"):
                CHECKER.check_boundary(root)

    def test_public_privacy_rights_route_cannot_disappear_from_ingress(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "deploy/nginx/walksafe-android-gateway.conf.example"
            source = path.read_text(encoding="utf-8")
            start = source.index("    location = /privacy/rights {")
            end = source.index("    }\n", start) + len("    }\n")
            path.write_text(source[:start] + source[end:], encoding="utf-8")
            with self.assertRaisesRegex(
                CHECKER.BoundaryError,
                "missing public non-API route",
            ):
                CHECKER.check_boundary(root)

    def test_openapi_cannot_expose_internal_token_header(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/android-gateway/openapi.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document.setdefault("info", {})["description"] = "x-walksafe-field-test-token"
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(CHECKER.BoundaryError, "internal credential"):
                CHECKER.check_boundary(root)

    def test_openapi_report_multipart_bounds_cannot_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/android-gateway/openapi.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            properties = document["paths"]["/api/reports/v2"]["post"]["requestBody"][
                "content"
            ]["multipart/form-data"]["schema"]["properties"]
            properties["image"]["x-max-bytes"] = 9 * 1024 * 1024
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(CHECKER.BoundaryError, "multipart bounds changed"):
                CHECKER.check_boundary(root)

    def test_legacy_web_allowlist_must_stay_empty(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/web/legacy-runtime-boundary.ts"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "[] as const",
                    '["/api/field-session"] as const',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "allowlist is not exactly empty"):
                CHECKER.check_boundary(root)

    def test_legacy_web_proxy_must_stay_gone(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/web/proxy.ts"
            path.write_text(
                path.read_text(encoding="utf-8").replace("status: 410", "status: 200"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "all-request 410"):
                CHECKER.check_boundary(root)

    def test_android_cannot_fall_back_to_next(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/android/app/build.gradle.kts"
            path.write_text(
                path.read_text(encoding="utf-8").replace("127.0.0.1:8081", "127.0.0.1:3000"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "independent loopback service"):
                CHECKER.check_boundary(root)

    def test_gateway_cannot_bind_publicly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/android-gateway/src/config.ts"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    'EXPECTED_GATEWAY_HOST = "127.0.0.1"',
                    'EXPECTED_GATEWAY_HOST = "0.0.0.0"',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "loopback configuration|non-exact bind"):
                CHECKER.check_boundary(root)

    def test_gateway_package_cannot_add_next(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/android-gateway/package.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["dependencies"] = {"next": "16.2.6"}
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(CHECKER.BoundaryError, "must not depend on Next"):
                CHECKER.check_boundary(root)

    def test_gateway_build_cannot_retain_stale_dist_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "apps/android-gateway/package.json"
            document = json.loads(path.read_text(encoding="utf-8"))
            document["scripts"]["build"] = "tsc"
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(CHECKER.BoundaryError, "remove package-local dist"):
                CHECKER.check_boundary(root)

    def test_workflow_must_test_the_gateway(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / ".github/workflows/quality.yml"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "          npm --prefix apps/android-gateway test\n",
                    "",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "quality workflow"):
                CHECKER.check_boundary(root)

    def test_product_boundary_cannot_claim_gateway_deployment(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "configs/walksafe_product_boundary_20260722.json"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '"deployment_status": "NOT_RUN"',
                    '"deployment_status": "PASS"',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "overstates Phase E"):
                CHECKER.check_boundary(root)

    def test_nginx_cannot_fall_back_to_backend(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "deploy/nginx/walksafe-android-gateway.conf.example"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "return 404;",
                    "proxy_pass http://127.0.0.1:8000;",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "exposes Backend"):
                CHECKER.check_boundary(root)

    def test_nginx_cannot_log_query_bearing_request_targets(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "deploy/nginx/walksafe-android-gateway.conf.example"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "access_log off;",
                    "access_log /var/log/nginx/access.log combined; # $request_uri",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "must not log query-bearing"):
                CHECKER.check_boundary(root)

    def test_nginx_generated_responses_must_be_no_store(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "deploy/nginx/walksafe-android-gateway.conf.example"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    '    add_header Cache-Control "no-store" always;\n',
                    "",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "ingress-generated responses"):
                CHECKER.check_boundary(root)

    def test_deployment_example_cannot_hide_its_not_applied_status(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._copy_contract(root)
            path = root / "deploy/systemd/walksafe-android-gateway.service"
            path.write_text(
                path.read_text(encoding="utf-8").replace("NOT_APPLIED", "APPLIED"),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(CHECKER.BoundaryError, "non-applied authority boundary"):
                CHECKER.check_boundary(root)


if __name__ == "__main__":
    unittest.main()
