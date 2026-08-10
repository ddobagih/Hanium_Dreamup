#!/usr/bin/env python3
"""Generate or verify the canonical, environment-independent OpenAPI contract."""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import sys
import tempfile


REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))
OUTPUT_PATH = REPO_ROOT / "contracts" / "walksafe.openapi.json"
WALKING_ROUTE_FIXTURE_PATH = REPO_ROOT / "contracts" / "fixtures" / "walking-route-v1.json"


def _configure_schema_environment(upload_dir: Path) -> None:
    keyring_path = upload_dir.parent / "report-image-keyring.json"
    keyring_path.write_text(
        json.dumps(
            {
                "generation": 1,
                "keys": [
                    {
                        "id": "contract-report-image-key-v1",
                        "material": base64.urlsafe_b64encode(b"C" * 32).decode("ascii").rstrip("="),
                        "state": "active",
                    }
                ],
                "previous_manifest_sha256": None,
                "schema": "walksafe.report-image-keyring.v1",
            },
            separators=(",", ":"),
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    keyring_path.chmod(0o400)
    values = {
        "DATABASE_URL": "postgresql+psycopg://walksafe_contract:disabled@127.0.0.1:1/walksafe_contract_test",
        "UPLOAD_DIR": str(upload_dir),
        "DETECT_V2_MODE": "fake",
        "DETECT_V2_IMAGE_SIZE": "768",
        "DETECT_V2_CUSTOM_TACTILE_MODEL_PATH": "",
        "DETECT_V2_COCO_MODEL_PATH": "",
        "DETECT_V2_UNIFIED_MODEL_PATH": "",
        "DETECT_V2_RUNTIME_CONFIG_PATH": "",
        "WALKSAFE_FIELD_TEST_SECURITY_ENABLED": "true",
        "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "false",
        "WALKSAFE_ENVIRONMENT": "test",
        "WALKSAFE_SOURCE_COMMIT": "",
        "WALKSAFE_FIELD_TEST_TOKEN": "contract-field-token-not-for-runtime",
        "WALKSAFE_ADMIN_TOKEN": "",
        "WALKSAFE_ADMIN_SECURITY_ENABLED": "true",
        "WALKSAFE_ADMIN_ID": "walksafe.admin",
        "WALKSAFE_ADMIN_TOTP_SECRET": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
        "WALKSAFE_REPORT_IMAGE_KEY_PROVIDER": "secret_file",
        "WALKSAFE_REPORT_IMAGE_KEY_FILE": str(keyring_path),
        "WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET": "",
        "TMAP_APP_KEY": "contract-generation-only",
        "INFERENCE_PROCESS_ISOLATION_ENABLED": "false",
    }
    os.environ.update(values)


def _render_schema(upload_dir: Path) -> str:
    _configure_schema_environment(upload_dir)
    from backend.app.main import app

    return json.dumps(app.openapi(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _render_walking_route_fixture() -> str:
    from backend.app.schemas import WalkingRouteRequest, WalkingRouteResponse

    request = WalkingRouteRequest.model_validate(
        {
            "origin": {"latitude": 37.0, "longitude": 127.0},
            "destination": {"latitude": 37.001, "longitude": 127.0},
            "priority": "STAIR_AVOID",
        }
    )
    response = WalkingRouteResponse.model_validate(
        {
            "schema_version": "walksafe.walking_route.v1",
            "provider": "tmap_pedestrian",
            "provider_route_id": "contract-route-1",
            "priority": "STAIR_AVOID",
            "summary": {"distance_m": 120, "duration_s": 100},
            "polyline": [request.origin.model_dump(), request.destination.model_dump()],
            "steps": [
                {
                    "index": 0,
                    "distance_m": 120,
                    "duration_s": 100,
                    "points": [request.origin.model_dump(), request.destination.model_dump()],
                    "instruction": "직진",
                    "road_name": None,
                    "turn_type": 11,
                    "facility_type": 15,
                }
            ],
            "guide_points": [
                {
                    "index": 0,
                    "point": request.destination.model_dump(),
                    "instruction": "목적지 도착",
                    "turn_type": 201,
                    "point_type": "E",
                    "facility_type": 15,
                    "distance_from_start_m": 120,
                    "remaining_distance_m": 0,
                    "bearing_deg": 0,
                }
            ],
            "provider_result_code": 0,
            "provider_result_message": "OK",
        }
    )
    payload = {
        "request": request.model_dump(mode="json"),
        "response": response.model_dump(mode="json"),
    }
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate the production-shape administrator Bearer contract with non-runtime fixture credentials."
    )
    parser.add_argument("--check", action="store_true", help="fail if the checked contract differs")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="walksafe-openapi-") as temp_dir:
        rendered = _render_schema(Path(temp_dir) / "uploads")
        walking_route_fixture = _render_walking_route_fixture()

    if args.check:
        stale_paths = [
            path
            for path, expected in (
                (OUTPUT_PATH, rendered),
                (WALKING_ROUTE_FIXTURE_PATH, walking_route_fixture),
            )
            if not path.is_file() or path.read_text(encoding="utf-8") != expected
        ]
        if stale_paths:
            raise SystemExit(
                "canonical HTTP contracts are stale: "
                + ", ".join(str(path.relative_to(REPO_ROOT)) for path in stale_paths)
                + "; run PYTHONPATH=. python scripts/generate_walksafe_openapi.py"
            )
        print("Canonical OpenAPI and walking-route fixture are current")
        return 0

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(rendered, encoding="utf-8")
    WALKING_ROUTE_FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    WALKING_ROUTE_FIXTURE_PATH.write_text(walking_route_fixture, encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH.relative_to(REPO_ROOT)} and {WALKING_ROUTE_FIXTURE_PATH.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
