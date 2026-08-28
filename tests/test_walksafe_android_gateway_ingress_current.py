from __future__ import annotations

import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
NGINX_PATH = ROOT / "deploy/nginx/walksafe-android-gateway.conf.example"
BOUNDARY_PATH = ROOT / "configs/walksafe_product_boundary_20260722.json"
OPENAPI_PATH = ROOT / "apps/android-gateway/openapi.json"

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

    def test_current_public_route_inventory_matches_openapi(self) -> None:
        products = self.boundary["products"]
        self.assertIsInstance(products, dict)
        gateway = products["android_api_gateway"]
        self.assertIsInstance(gateway, dict)
        self.assertEqual(gateway["public_routes"], EXPECTED_PUBLIC_ROUTES)
        self.assertEqual(gateway["public_non_api_routes"], [PUBLIC_RIGHTS_ROUTE])

        paths = self.openapi["paths"]
        self.assertIsInstance(paths, dict)
        self.assertEqual(
            set(paths),
            set(EXPECTED_PUBLIC_ROUTES) | {PUBLIC_RIGHTS_ROUTE},
        )

    def test_ingress_proxies_only_eight_routes_and_privacy_rights(self) -> None:
        expected_headers = [
            "location = /api/field-session {",
            "location = /api/navigation/walking {",
            "location = /api/navigation/destinations/search {",
            "location = /api/reports/v2 {",
            "location = /api/field-walk {",
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
            len(EXPECTED_PUBLIC_ROUTES) + 1,
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
