from __future__ import annotations

import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
NGINX_PATH = ROOT / "deploy/nginx/walksafe-android-gateway.conf.example"
BOUNDARY_PATH = ROOT / "configs/walksafe_product_boundary_20260722.json"
OPENAPI_PATH = ROOT / "apps/android-gateway/openapi.json"
BACKEND_OPENAPI_PATH = ROOT / "contracts/walksafe.openapi.json"

EXPECTED_PUBLIC_ROUTES = [
    "/api/field-session",
    "/api/navigation/walking",
    "/api/navigation/destinations/search",
    "/api/reports/v2",
    "/api/field-walk",
    "/privacy/account-deletions",
    "/privacy/account-deletions/{request_id}/status",
    "/privacy/account-deletions/{request_id}/device-evidence",
]
PUBLIC_RIGHTS_ROUTE = "/privacy/rights"
STATUS_PATTERN = r"^/privacy/account-deletions/[A-Za-z0-9_-]{16,128}/status$"
EVIDENCE_PATTERN = r"^/privacy/account-deletions/[A-Za-z0-9_-]{16,128}/device-evidence$"
UUID_PATTERN = (
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}"
)
REPORT_STATUS_PATTERN = rf"^/api/reports/v2/{UUID_PATTERN}/status$"
MY_REPORT_DETAIL_PATTERN = rf"^/api/reports/mine/{UUID_PATTERN}$"
MY_REPORT_CONTENT_PATTERN = rf"^/api/reports/mine/{UUID_PATTERN}/content$"
MY_REPORT_CORRECTIONS_PATTERN = rf"^/api/reports/mine/{UUID_PATTERN}/corrections$"
MY_REPORT_REQUESTS_PATTERN = rf"^/api/reports/mine/{UUID_PATTERN}/requests$"
MY_REPORT_REQUEST_DETAIL_PATTERN = (
    rf"^/api/reports/mine/{UUID_PATTERN}/requests/{UUID_PATTERN}$"
)
MY_REPORT_DELETION_PATTERN = rf"^/api/reports/mine/deletions/{UUID_PATTERN}$"


def strict_json(path: Path) -> dict[str, object]:
    def reject_duplicates(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    decoded = json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicates,
    )
    if not isinstance(decoded, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return decoded


def location_block(source: str, header: str) -> str:
    lines = source.splitlines(keepends=True)
    start = lines.index(f"    {header} {{\n")
    for end in range(start + 1, len(lines)):
        if lines[end] == "    }\n":
            return "".join(lines[start : end + 1])
    raise ValueError(f"location block is not closed: {header}")


class WalkSafeAndroidGatewayIngressCurrentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.nginx = NGINX_PATH.read_text(encoding="utf-8")
        cls.boundary = strict_json(BOUNDARY_PATH)
        cls.openapi = strict_json(OPENAPI_PATH)
        cls.backend_openapi = strict_json(BACKEND_OPENAPI_PATH)

    def test_current_user_report_contract_methods_are_allowlisted(self) -> None:
        expected_methods = {
            "/reports/v2/{report_id}/status": "get",
            "/reports/mine": "get",
            "/reports/mine/requests/history": "get",
            "/reports/mine/deletions/{request_id}": "get",
            "/reports/mine/{report_id}": "get",
            "/reports/mine/{report_id}/content": "get",
            "/reports/mine/{report_id}/corrections": "post",
            "/reports/mine/{report_id}/requests": "post",
            "/reports/mine/{report_id}/requests/{request_id}": "get",
        }
        paths = self.backend_openapi["paths"]
        self.assertIsInstance(paths, dict)

        location_methods = {
            f'location ~ "{REPORT_STATUS_PATTERN}"': "GET",
            "location = /api/reports/mine": "GET",
            "location = /api/reports/mine/requests/history": "GET",
            f'location ~ "{MY_REPORT_DELETION_PATTERN}"': "GET",
            f'location ~ "{MY_REPORT_DETAIL_PATTERN}"': "GET",
            f'location ~ "{MY_REPORT_CONTENT_PATTERN}"': "GET",
            f'location ~ "{MY_REPORT_CORRECTIONS_PATTERN}"': "POST",
            f'location ~ "{MY_REPORT_REQUESTS_PATTERN}"': "POST",
            f'location ~ "{MY_REPORT_REQUEST_DETAIL_PATTERN}"': "GET",
        }
        self.assertEqual(len(location_methods), len(expected_methods))

        for path, method in expected_methods.items():
            with self.subTest(contract_path=path):
                operation = paths[path]
                self.assertIsInstance(operation, dict)
                actual_methods = set(operation) & {
                    "get",
                    "post",
                    "put",
                    "patch",
                    "delete",
                }
                self.assertEqual(actual_methods, {method})

        for header, method in location_methods.items():
            with self.subTest(location=header):
                block = location_block(self.nginx, header)
                self.assertIn(f"if ($request_method != {method})", block)
                self.assertIn("proxy_pass http://127.0.0.1:8081;", block)
                self.assertEqual(block.count("proxy_pass "), 1)
                expected_limit = "4k" if method == "POST" else "1k"
                self.assertIn(f"client_max_body_size {expected_limit};", block)

    def test_user_report_identifier_locations_remain_exact_and_concealed(self) -> None:
        report_id = "12345678-1234-1234-1234-123456789abc"
        request_id = "abcdefab-cdef-cdef-cdef-abcdefabcdef"
        valid_paths = {
            REPORT_STATUS_PATTERN: f"/api/reports/v2/{report_id}/status",
            MY_REPORT_DETAIL_PATTERN: f"/api/reports/mine/{report_id}",
            MY_REPORT_CONTENT_PATTERN: f"/api/reports/mine/{report_id}/content",
            MY_REPORT_CORRECTIONS_PATTERN: f"/api/reports/mine/{report_id}/corrections",
            MY_REPORT_REQUESTS_PATTERN: f"/api/reports/mine/{report_id}/requests",
            MY_REPORT_REQUEST_DETAIL_PATTERN: (
                f"/api/reports/mine/{report_id}/requests/{request_id}"
            ),
            MY_REPORT_DELETION_PATTERN: (
                f"/api/reports/mine/deletions/{request_id}"
            ),
        }
        for pattern, path in valid_paths.items():
            with self.subTest(valid_path=path):
                self.assertIsNotNone(re.fullmatch(pattern, path))
                self.assertIsNone(re.fullmatch(pattern, f"{path}/"))
                uppercase_id_path = path.replace(
                    report_id, report_id.upper()
                ).replace(request_id, request_id.upper())
                self.assertIsNone(re.fullmatch(pattern, uppercase_id_path))

        catch_all = location_block(self.nginx, "location /")
        self.assertIn("return 404;", catch_all)
        self.assertNotIn("proxy_pass", catch_all)

    def test_declared_public_route_inventory_is_in_current_openapi(self) -> None:
        products = self.boundary["products"]
        self.assertIsInstance(products, dict)
        gateway = products["android_api_gateway"]
        self.assertIsInstance(gateway, dict)
        self.assertEqual(gateway["public_routes"], EXPECTED_PUBLIC_ROUTES)
        self.assertEqual(gateway["public_non_api_routes"], [PUBLIC_RIGHTS_ROUTE])

        paths = self.openapi["paths"]
        self.assertIsInstance(paths, dict)
        self.assertTrue(
            (set(EXPECTED_PUBLIC_ROUTES) | {PUBLIC_RIGHTS_ROUTE}).issubset(paths),
        )

    def test_ingress_proxies_only_current_openapi_routes(self) -> None:
        expected_headers = [
            "location = /api/account-enrollments/email-otp {",
            "location = /api/accounts {",
            "location = /api/field-session {",
            "location = /api/speech/stt {",
            "location = /api/speech/tts {",
            "location = /api/navigation/walking {",
            "location = /api/navigation/destinations/search {",
            "location = /api/reports/v2 {",
            f'location ~ "{REPORT_STATUS_PATTERN}" {{',
            "location = /api/reports/mine {",
            "location = /api/reports/mine/requests/history {",
            f'location ~ "{MY_REPORT_DELETION_PATTERN}" {{',
            f'location ~ "{MY_REPORT_DETAIL_PATTERN}" {{',
            f'location ~ "{MY_REPORT_CONTENT_PATTERN}" {{',
            f'location ~ "{MY_REPORT_CORRECTIONS_PATTERN}" {{',
            f'location ~ "{MY_REPORT_REQUESTS_PATTERN}" {{',
            f'location ~ "{MY_REPORT_REQUEST_DETAIL_PATTERN}" {{',
            "location = /api/field-walk {",
            (
                'location ~ "^/api/raw-collections/[0-9a-f]{8}-[0-9a-f]{4}-'
                '[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/manifest$" {'
            ),
            (
                'location ~ "^/api/raw-collections/[0-9a-f]{8}-[0-9a-f]{4}-'
                '[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/objects/'
                '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-'
                '[0-9a-f]{12}/chunks/(?:0|[1-9][0-9]{0,2}|1[0-9]{3}|'
                '20(?:[0-3][0-9]|4[0-7]))$" {'
            ),
            (
                'location ~ "^/api/raw-collections/[0-9a-f]{8}-[0-9a-f]{4}-'
                '[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" {'
            ),
            (
                'location ~ "^/api/raw-collections/[0-9a-f]{8}-[0-9a-f]{4}-'
                '[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/commit$" {'
            ),
            "location = /privacy/rights {",
            "location = /privacy/account-deletions {",
            f'location ~ "{STATUS_PATTERN}" {{',
            f'location ~ "{EVIDENCE_PATTERN}" {{',
            "location / {",
        ]
        actual_headers = [
            line.strip()
            for line in self.nginx.splitlines()
            if line.startswith("    location ")
        ]
        self.assertEqual(actual_headers, expected_headers)
        self.assertEqual(
            self.nginx.count("proxy_pass http://127.0.0.1:8081;"),
            len(self.openapi["paths"]),
        )

    def test_each_upstream_location_has_the_expected_body_limit(self) -> None:
        exact_limits = {
            "location = /api/field-session": "4k",
            "location = /api/navigation/walking": "16k",
            "location = /api/navigation/destinations/search": "1k",
            "location = /api/reports/v2": "9m",
            "location = /api/field-walk": "1k",
            "location = /privacy/rights": "1k",
            "location = /privacy/account-deletions": "4k",
            f'location ~ "{STATUS_PATTERN}"': "1k",
            f'location ~ "{EVIDENCE_PATTERN}"': "16k",
        }
        for header, limit in exact_limits.items():
            with self.subTest(header=header):
                block = location_block(self.nginx, header)
                self.assertIn(f"client_max_body_size {limit};", block)
                self.assertEqual(block.count("client_max_body_size "), 1)
                self.assertIn("proxy_pass http://127.0.0.1:8081;", block)
                self.assertEqual(block.count("proxy_pass "), 1)

    def test_account_deletion_request_id_regex_is_exact_and_bounded(self) -> None:
        patterns = {
            "status": re.compile(STATUS_PATTERN),
            "device-evidence": re.compile(EVIDENCE_PATTERN),
        }
        for suffix, pattern in patterns.items():
            for request_id in ("A" * 16, "aZ09_-" + "b" * 122):
                with self.subTest(suffix=suffix, request_id_length=len(request_id)):
                    path = f"/privacy/account-deletions/{request_id}/{suffix}"
                    self.assertIsNotNone(pattern.fullmatch(path))

            invalid_paths = [
                f"/privacy/account-deletions/{'A' * 15}/{suffix}",
                f"/privacy/account-deletions/{'A' * 129}/{suffix}",
                f"/privacy/account-deletions/{'A' * 15}!/{suffix}",
                f"/privacy/account-deletions/{'A' * 16}/{suffix}/",
                f"/privacy/account-deletions/{'A' * 16}/{suffix}/extra",
            ]
            for path in invalid_paths:
                with self.subTest(suffix=suffix, invalid_path=path):
                    self.assertIsNone(pattern.fullmatch(path))

    def test_ingress_safety_boundaries_remain_fail_closed(self) -> None:
        catch_all = location_block(self.nginx, "location /")
        self.assertIn("return 404;", catch_all)
        self.assertNotIn("proxy_pass", catch_all)
        self.assertIn('add_header Cache-Control "no-store" always;', self.nginx)
        self.assertIn("access_log off;", self.nginx)
        self.assertNotIn("$request_uri", self.nginx)
        self.assertNotIn("proxy_pass http://127.0.0.1:8000", self.nginx)
        self.assertNotIn("127.0.0.1:3000", self.nginx)

    def test_internal_gateway_health_is_not_exposed_by_ingress(self) -> None:
        self.assertNotIn("/internal/health", self.nginx)
        catch_all = location_block(self.nginx, "location /")
        self.assertIn("return 404;", catch_all)
        self.assertNotIn("proxy_pass", catch_all)


if __name__ == "__main__":
    unittest.main()
