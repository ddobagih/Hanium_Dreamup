"""Environment-backed backend settings and strict value parsers.

Defaults keep fake detection and debug logging suitable for local development,
but API authorization fails closed unless local tests explicitly disable it.
Deployment code must override paths and credentials rather than infer readiness.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import math
import os
import re
import stat
from functools import lru_cache
import ipaddress
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

from dotenv import load_dotenv
from model.two_model_runtime import load_threshold_config

from backend.app.schemas import CLASS_ORDER


DEFAULT_MAX_UPLOAD_BYTES = 8 * 1024 * 1024
MAX_UPLOAD_BYTES_LIMIT = 32 * 1024 * 1024
DEFAULT_ALLOWED_IMAGE_CONTENT_TYPES = "image/jpeg,image/png,image/webp"
DEFAULT_MODEL_CLASS_ORDER = CLASS_ORDER
DEFAULT_MODEL_CLASS_ORDER_ENV = ",".join(DEFAULT_MODEL_CLASS_ORDER)
DEFAULT_MODEL_CONFIDENCE_THRESHOLD = 0.35
DEFAULT_MODEL_IOU_THRESHOLD = 0.7
DEFAULT_MODEL_IMAGE_SIZE = 640
DEFAULT_DETECT_V2_MODE = "fake"
DEFAULT_DETECT_V2_IMAGE_SIZE = 768
DEFAULT_WALKING_ROUTE_PROVIDER = "tmap_pedestrian"
SUPPORTED_WALKING_ROUTE_PROVIDERS = frozenset({"tmap_pedestrian"})
DEFAULT_TMAP_PEDESTRIAN_ROUTE_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian"
DEFAULT_TMAP_POI_SEARCH_URL = "https://apis.openapi.sk.com/tmap/pois"
DEFAULT_TMAP_PEDESTRIAN_API_VERSION = "1"
DEFAULT_TMAP_TIMEOUT_SECONDS = 4.0
MAX_TMAP_TIMEOUT_SECONDS = 30.0
DEFAULT_TMAP_READINESS_LIVE_PROBE_ENABLED = "false"
DEFAULT_TMAP_READINESS_PROBE_TIMEOUT_SECONDS = 3.0
DEFAULT_TMAP_READINESS_SUCCESS_MAX_AGE_SECONDS = 300.0
DEFAULT_TMAP_PEDESTRIAN_SPEED_KMH = 4.0
MAX_TMAP_PEDESTRIAN_SPEED_KMH = 20.0
DEFAULT_TMAP_POI_PROVIDER = "live"
SUPPORTED_TMAP_POI_PROVIDERS = frozenset({"live", "mock"})
DEFAULT_MAX_REPORT_METADATA_BYTES = 64 * 1024
MAX_REPORT_METADATA_BYTES_LIMIT = 1024 * 1024
DEFAULT_MAX_ANDROID_DEBUG_LOG_BYTES = 64 * 1024
DEFAULT_ANDROID_DEBUG_LOG_RETENTION_DAYS = 7
DEFAULT_ANDROID_DEBUG_LOG_ENABLED = "false"
DEFAULT_INFERENCE_TIMEOUT_SECONDS = 2.0
DEFAULT_INFERENCE_STARTUP_TIMEOUT_SECONDS = 30.0
DEFAULT_DATABASE_CONNECT_TIMEOUT_SECONDS = 5
DEFAULT_DATABASE_STATEMENT_TIMEOUT_MS = 10_000
DEFAULT_FIELD_TEST_SECURITY_ENABLED = "true"
DEFAULT_ADMIN_SECURITY_ENABLED = "false"
DEFAULT_ADMIN_SESSION_TTL_SECONDS = 12 * 60 * 60
DEFAULT_ADMIN_STEP_UP_TTL_SECONDS = 5 * 60
DEFAULT_ADMIN_RECOVERY_TTL_SECONDS = 15 * 60
DEFAULT_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS = 5
DEFAULT_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS = 5 * 60
DEFAULT_ADMIN_CREDENTIAL_ISSUER_KEY_FILE = Path(
    "/etc/walksafe/admin-credential-issuer.key"
)
MIN_FIELD_TEST_TOKEN_LENGTH = 24
MIN_PRIVACY_HMAC_SECRET_BYTES = 32
DEPLOYMENT_ENVIRONMENTS = frozenset({"field", "staging", "production"})
SUPPORTED_WALKSAFE_ENVIRONMENTS = frozenset(
    {"development", "test", *DEPLOYMENT_ENVIRONMENTS}
)
FULL_GIT_COMMIT = re.compile(r"^[0-9a-f]{40}$")
ADMIN_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
TOTP_SECRET_PATTERN = re.compile(r"^[A-Z2-7]+$")
KEY_BOUNDARY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}$")
DATABASE_ROLE_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_.-]{0,62}$")


def _env_text(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip()


def _load_local_dotenv(backend_root: Path) -> None:
    environment = _env_text("WALKSAFE_ENVIRONMENT", "development").lower()
    if environment not in DEPLOYMENT_ENVIRONMENTS:
        load_dotenv(backend_root / ".env")
        loaded_environment = _env_text(
            "WALKSAFE_ENVIRONMENT", "development"
        ).lower()
        if loaded_environment in DEPLOYMENT_ENVIRONMENTS:
            raise ValueError(
                "deployment environment must not be selected from repository .env"
            )


def _has_minimum_privacy_hmac_bytes(value: str) -> bool:
    return len(value.encode("utf-8")) >= MIN_PRIVACY_HMAC_SECRET_BYTES


def _parse_model_class_order(raw_value: str) -> tuple[str, ...]:
    if not raw_value:
        return DEFAULT_MODEL_CLASS_ORDER

    class_order = tuple(
        class_name.strip() for class_name in raw_value.split(",") if class_name.strip()
    )
    if class_order != DEFAULT_MODEL_CLASS_ORDER:
        raise ValueError(f"MODEL_CLASS_ORDER must be {DEFAULT_MODEL_CLASS_ORDER_ENV}")
    return class_order


def _parse_unit_float(name: str, default: float) -> float:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default

    value = float(raw_value)
    if not math.isfinite(value) or value < 0 or value > 1:
        raise ValueError(f"{name} must be finite and between 0 and 1")
    return value


def _parse_positive_int(name: str, default: int) -> int:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default

    value = int(raw_value)
    if value <= 0:
        raise ValueError(f"{name} must be greater than 0")
    return value


def _parse_nonnegative_int(name: str, default: int) -> int:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default
    value = int(raw_value)
    if value < 0:
        raise ValueError(f"{name} must be nonnegative")
    return value


def _parse_positive_float(name: str, default: float) -> float:
    raw_value = _env_text(name, str(default))
    if not raw_value:
        return default

    value = float(raw_value)
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{name} must be finite and greater than 0")
    return value


def _parse_bool(name: str, default: str = "false") -> bool:
    value = _env_text(name, default).lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off", ""}:
        return False
    raise ValueError(f"{name} must be a boolean")


def validate_admin_totp_secret(raw_secret: str) -> str:
    secret = raw_secret.strip().replace(" ", "").upper()
    message = (
        "WALKSAFE_ADMIN_TOTP_SECRET must be canonical unpadded Base32 "
        "containing at least 20 decoded bytes"
    )
    if len(secret) < 32 or TOTP_SECRET_PATTERN.fullmatch(secret) is None:
        raise ValueError(message)
    try:
        decoded = base64.b32decode(secret + "=" * (-len(secret) % 8), casefold=False)
    except (binascii.Error, ValueError) as exc:
        raise ValueError(message) from exc
    canonical = base64.b32encode(decoded).decode("ascii").rstrip("=")
    if len(decoded) < 20 or canonical != secret:
        raise ValueError(message)
    return secret


def _parse_admin_credential_issuer_key_file(
    raw_path: str,
    environment: str,
) -> Path | None:
    if raw_path:
        path = Path(raw_path).expanduser()
    elif environment in DEPLOYMENT_ENVIRONMENTS:
        path = DEFAULT_ADMIN_CREDENTIAL_ISSUER_KEY_FILE
    else:
        return None
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise ValueError(
            "WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY_FILE must be a normalized absolute path"
        )
    return path


def _parse_walking_route_provider(raw_value: str) -> str:
    provider = raw_value.strip().lower() or DEFAULT_WALKING_ROUTE_PROVIDER
    if provider not in SUPPORTED_WALKING_ROUTE_PROVIDERS:
        supported = ", ".join(sorted(SUPPORTED_WALKING_ROUTE_PROVIDERS))
        raise ValueError(f"WALKING_ROUTE_PROVIDER must be one of: {supported}")
    return provider


def _parse_tmap_poi_provider(raw_value: str) -> str:
    provider = raw_value.strip().lower() or DEFAULT_TMAP_POI_PROVIDER
    if provider not in SUPPORTED_TMAP_POI_PROVIDERS:
        supported = ", ".join(sorted(SUPPORTED_TMAP_POI_PROVIDERS))
        raise ValueError(f"TMAP_POI_PROVIDER must be one of: {supported}")
    return provider


def _parse_database_url(raw_value: str) -> str:
    database_url = raw_value.strip()
    parsed = urlsplit(database_url)
    if parsed.scheme.lower() != "postgresql+psycopg" or not parsed.path.lstrip("/"):
        raise ValueError("DATABASE_URL must use postgresql+psycopg and name a database")
    return database_url


def _database_host_is_loopback(host: str) -> bool:
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def _database_host_is_local(host: str) -> bool:
    return not host or host.startswith(("/", "@")) or _database_host_is_loopback(host)


def _validate_deployment_database_transport(database_url: str) -> None:
    parsed = urlsplit(database_url)
    parameters = parse_qs(parsed.query, keep_blank_values=True)
    raw_authority_host = parsed.hostname or ""
    decoded_authority_host = unquote(raw_authority_host)
    if (
        "%" in raw_authority_host
        or decoded_authority_host != raw_authority_host
        or any(character in decoded_authority_host for character in (",", "/", "\\"))
        or any(ord(character) < 0x21 or ord(character) == 0x7F for character in decoded_authority_host)
    ):
        raise ValueError("deployment DATABASE_URL authority host must name one literal host")
    if "service" in parameters or "servicefile" in parameters:
        raise ValueError(
            "deployment DATABASE_URL database transport must declare host and TLS directly, "
            "not through a libpq service"
        )
    ambient_transport = [
        name
        for name in ("PGHOST", "PGHOSTADDR", "PGSERVICE", "PGSERVICEFILE", "PGGSSENCMODE")
        if os.getenv(name, "").strip()
    ]
    if ambient_transport:
        raise ValueError(
            "deployment database transport must not use ambient libpq host/service settings: "
            + ", ".join(ambient_transport)
        )
    query_hosts = parameters.get("host", [])
    if len(query_hosts) > 1:
        raise ValueError("DATABASE_URL must declare at most one host parameter")
    if query_hosts:
        hosts = query_hosts[0].split(",")
    else:
        hosts = (parsed.hostname or "").split(",")
    query_hostaddrs = parameters.get("hostaddr", [])
    if len(query_hostaddrs) > 1:
        raise ValueError("DATABASE_URL must declare at most one hostaddr parameter")
    hostaddrs = query_hostaddrs[0].split(",") if query_hostaddrs else []
    local_transport = all(_database_host_is_local(host) for host in [*hosts, *hostaddrs])
    if local_transport:
        return
    if parameters.get("sslmode") != ["verify-full"] or parameters.get("gssencmode") != ["disable"]:
        raise ValueError(
            "remote deployment DATABASE_URL must use sslmode=verify-full and gssencmode=disable; "
            "unencrypted database connections are allowed only over loopback or Unix sockets"
        )


def _validate_provider_url(name: str, raw_url: str, allowed_hosts: frozenset[str]) -> str:
    parsed = urlsplit(raw_url)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in allowed_hosts
        or parsed.port not in {None, 443}
        or parsed.username is not None
        or parsed.password is not None
        or not parsed.path.startswith("/")
        or parsed.fragment
    ):
        raise ValueError(f"{name} must use the official HTTPS provider host")
    return raw_url


def migration_database_url() -> str:
    """Load only the database settings needed by Alembic."""
    backend_root = Path(__file__).resolve().parents[1]
    _load_local_dotenv(backend_root)
    environment = _env_text("WALKSAFE_ENVIRONMENT", "development").lower()
    if environment not in SUPPORTED_WALKSAFE_ENVIRONMENTS:
        raise ValueError("WALKSAFE_ENVIRONMENT has an unsupported value")
    raw_runtime_database_url = _env_text("DATABASE_URL")
    configured_migration_url = os.getenv("WALKSAFE_MIGRATION_DATABASE_URL", "").strip()
    database_url = _parse_database_url(
        configured_migration_url or raw_runtime_database_url
    )
    if environment in DEPLOYMENT_ENVIRONMENTS:
        if not configured_migration_url:
            raise ValueError(
                "deployment migrations require WALKSAFE_MIGRATION_DATABASE_URL"
            )
        _validate_deployment_database_transport(database_url)
        configured_runtime_role = _env_text("WALKSAFE_RUNTIME_DATABASE_ROLE")
        runtime_role_from_url = (
            unquote(urlsplit(raw_runtime_database_url).username or "")
            if raw_runtime_database_url
            else ""
        )
        if configured_runtime_role and not DATABASE_ROLE_PATTERN.fullmatch(
            configured_runtime_role
        ):
            raise ValueError(
                "WALKSAFE_RUNTIME_DATABASE_ROLE must name one canonical database role"
            )
        if (
            configured_runtime_role
            and runtime_role_from_url
            and configured_runtime_role != runtime_role_from_url
        ):
            raise ValueError(
                "WALKSAFE_RUNTIME_DATABASE_ROLE must match the DATABASE_URL role"
            )
        runtime_role = configured_runtime_role or runtime_role_from_url
        if not runtime_role:
            raise ValueError(
                "deployment migrations require WALKSAFE_RUNTIME_DATABASE_ROLE "
                "when DATABASE_URL is not present"
            )
        migration_role = unquote(urlsplit(database_url).username or "")
        if migration_role == runtime_role:
            raise ValueError(
                "migration and runtime database roles must be distinct"
            )
    return database_url


class Settings:
    def __init__(self) -> None:
        backend_root = Path(__file__).resolve().parents[1]
        _load_local_dotenv(backend_root)
        self.database_url = _parse_database_url(
            os.getenv(
                "DATABASE_URL",
                "postgresql+psycopg://walksafe:walksafe@localhost:5432/walksafe",
            )
        )
        self.database_connect_timeout_seconds = _parse_positive_int(
            "DATABASE_CONNECT_TIMEOUT_SECONDS",
            DEFAULT_DATABASE_CONNECT_TIMEOUT_SECONDS,
        )
        if self.database_connect_timeout_seconds > 30:
            raise ValueError("DATABASE_CONNECT_TIMEOUT_SECONDS must be at most 30")
        self.database_statement_timeout_ms = _parse_positive_int(
            "DATABASE_STATEMENT_TIMEOUT_MS",
            DEFAULT_DATABASE_STATEMENT_TIMEOUT_MS,
        )
        if self.database_statement_timeout_ms > 120_000:
            raise ValueError("DATABASE_STATEMENT_TIMEOUT_MS must be at most 120000")
        raw_upload_dir = Path(os.getenv("UPLOAD_DIR", str(backend_root / "uploads"))).expanduser()
        self._upload_dir_was_absolute = raw_upload_dir.is_absolute()
        self._configured_upload_dir = raw_upload_dir.absolute()
        self.upload_dir = raw_upload_dir.resolve()
        self.max_upload_bytes = _parse_positive_int("MAX_UPLOAD_BYTES", DEFAULT_MAX_UPLOAD_BYTES)
        if self.max_upload_bytes > MAX_UPLOAD_BYTES_LIMIT:
            raise ValueError(f"MAX_UPLOAD_BYTES must be at most {MAX_UPLOAD_BYTES_LIMIT}")
        self.allowed_image_content_types = {
            content_type.strip()
            for content_type in os.getenv("ALLOWED_IMAGE_CONTENT_TYPES", DEFAULT_ALLOWED_IMAGE_CONTENT_TYPES).split(",")
            if content_type.strip()
        }
        supported_image_content_types = set(DEFAULT_ALLOWED_IMAGE_CONTENT_TYPES.split(","))
        if not self.allowed_image_content_types or not self.allowed_image_content_types <= supported_image_content_types:
            raise ValueError("ALLOWED_IMAGE_CONTENT_TYPES must contain only supported image MIME types")
        self.report_image_key_provider = _env_text("WALKSAFE_REPORT_IMAGE_KEY_PROVIDER").lower()
        if self.report_image_key_provider not in {"secret_file", "kms_agent"}:
            raise ValueError(
                "WALKSAFE_REPORT_IMAGE_KEY_PROVIDER must be secret_file or kms_agent"
            )
        raw_report_image_key_file = _env_text("WALKSAFE_REPORT_IMAGE_KEY_FILE")
        self.report_image_key_file = (
            Path(raw_report_image_key_file).expanduser()
            if raw_report_image_key_file
            else None
        )
        raw_report_image_kms_socket = _env_text("WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET")
        self.report_image_kms_agent_socket = (
            Path(raw_report_image_kms_socket).expanduser()
            if raw_report_image_kms_socket
            else None
        )
        self.report_image_kms_agent_peer_uid = _parse_nonnegative_int(
            "WALKSAFE_REPORT_IMAGE_KMS_AGENT_PEER_UID",
            0,
        )
        self.report_image_kms_agent_timeout_seconds = _parse_positive_float(
            "WALKSAFE_REPORT_IMAGE_KMS_AGENT_TIMEOUT_SECONDS",
            2.0,
        )
        if self.report_image_kms_agent_timeout_seconds > 5:
            raise ValueError(
                "WALKSAFE_REPORT_IMAGE_KMS_AGENT_TIMEOUT_SECONDS must be at most 5"
            )
        if self.report_image_key_provider == "secret_file":
            if self.report_image_key_file is None or self.report_image_kms_agent_socket is not None:
                raise ValueError(
                    "secret_file report image keys require only WALKSAFE_REPORT_IMAGE_KEY_FILE"
                )
        elif self.report_image_kms_agent_socket is None or self.report_image_key_file is not None:
            raise ValueError(
                "kms_agent report image keys require only WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET"
            )
        self.report_original_grant_ttl_seconds = _parse_positive_int(
            "WALKSAFE_REPORT_ORIGINAL_GRANT_TTL_SECONDS",
            120,
        )
        if self.report_original_grant_ttl_seconds > 300:
            raise ValueError(
                "WALKSAFE_REPORT_ORIGINAL_GRANT_TTL_SECONDS must be at most 300"
            )
        self.database_at_rest_encryption_confirmed = _parse_bool(
            "WALKSAFE_DATABASE_AT_REST_ENCRYPTION_CONFIRMED",
            "false",
        )
        self.database_transport_security_confirmed = _parse_bool(
            "WALKSAFE_DATABASE_TRANSPORT_SECURITY_CONFIRMED",
            "false",
        )
        self.database_encryption_key_boundary = _env_text(
            "WALKSAFE_DATABASE_ENCRYPTION_KEY_BOUNDARY"
        )
        self.report_image_key_boundary = _env_text(
            "WALKSAFE_REPORT_IMAGE_KEY_BOUNDARY"
        )
        model_path = _env_text("MODEL_ARTIFACT_PATH")
        self.model_artifact_path = Path(model_path).expanduser().resolve() if model_path else None
        self.model_version = _env_text("MODEL_VERSION") or None
        self.model_class_order = _parse_model_class_order(
            _env_text("MODEL_CLASS_ORDER", DEFAULT_MODEL_CLASS_ORDER_ENV)
        )
        self.model_confidence_threshold = _parse_unit_float(
            "MODEL_CONFIDENCE_THRESHOLD",
            DEFAULT_MODEL_CONFIDENCE_THRESHOLD,
        )
        self.model_iou_threshold = _parse_unit_float(
            "MODEL_IOU_THRESHOLD",
            DEFAULT_MODEL_IOU_THRESHOLD,
        )
        self.model_image_size = _parse_positive_int("MODEL_IMAGE_SIZE", DEFAULT_MODEL_IMAGE_SIZE)
        self.detect_v2_mode = _env_text("DETECT_V2_MODE", DEFAULT_DETECT_V2_MODE).lower() or DEFAULT_DETECT_V2_MODE
        self.detect_v2_image_size = _parse_positive_int(
            "DETECT_V2_IMAGE_SIZE",
            DEFAULT_DETECT_V2_IMAGE_SIZE,
        )
        detect_v2_custom_tactile_model_path = _env_text("DETECT_V2_CUSTOM_TACTILE_MODEL_PATH")
        self.detect_v2_custom_tactile_model_path = (
            Path(detect_v2_custom_tactile_model_path).expanduser()
            if detect_v2_custom_tactile_model_path
            else None
        )
        detect_v2_coco_model_path = _env_text("DETECT_V2_COCO_MODEL_PATH")
        self.detect_v2_coco_model_path = (
            Path(detect_v2_coco_model_path).expanduser()
            if detect_v2_coco_model_path
            else None
        )
        detect_v2_unified_model_path = _env_text("DETECT_V2_UNIFIED_MODEL_PATH")
        self.detect_v2_unified_model_path = (
            Path(detect_v2_unified_model_path).expanduser()
            if detect_v2_unified_model_path
            else None
        )
        detect_v2_runtime_config_path = _env_text("DETECT_V2_RUNTIME_CONFIG_PATH")
        self.detect_v2_runtime_config_path = (
            Path(detect_v2_runtime_config_path).expanduser()
            if detect_v2_runtime_config_path
            else None
        )
        self.walking_route_provider = _parse_walking_route_provider(
            _env_text("WALKING_ROUTE_PROVIDER", DEFAULT_WALKING_ROUTE_PROVIDER)
        )
        self.tmap_app_key = _env_text("TMAP_APP_KEY")
        self.tmap_pedestrian_route_url = _validate_provider_url(
            "TMAP_PEDESTRIAN_ROUTE_URL",
            _env_text("TMAP_PEDESTRIAN_ROUTE_URL", DEFAULT_TMAP_PEDESTRIAN_ROUTE_URL),
            frozenset({"apis.openapi.sk.com"}),
        )
        self.tmap_pedestrian_api_version = _env_text(
            "TMAP_PEDESTRIAN_API_VERSION",
            DEFAULT_TMAP_PEDESTRIAN_API_VERSION,
        )
        self.tmap_poi_search_url = _validate_provider_url(
            "TMAP_POI_SEARCH_URL",
            _env_text("TMAP_POI_SEARCH_URL", DEFAULT_TMAP_POI_SEARCH_URL),
            frozenset({"apis.openapi.sk.com"}),
        )
        self.tmap_poi_provider = _parse_tmap_poi_provider(
            _env_text("TMAP_POI_PROVIDER", DEFAULT_TMAP_POI_PROVIDER)
        )
        self.tmap_timeout_seconds = _parse_positive_float(
            "TMAP_TIMEOUT_SECONDS",
            DEFAULT_TMAP_TIMEOUT_SECONDS,
        )
        if self.tmap_timeout_seconds > MAX_TMAP_TIMEOUT_SECONDS:
            raise ValueError(
                f"TMAP_TIMEOUT_SECONDS must be at most {MAX_TMAP_TIMEOUT_SECONDS:g}"
            )
        self.tmap_readiness_live_probe_enabled = _parse_bool(
            "TMAP_READINESS_LIVE_PROBE_ENABLED",
            DEFAULT_TMAP_READINESS_LIVE_PROBE_ENABLED,
        )
        self.tmap_readiness_probe_timeout_seconds = _parse_positive_float(
            "TMAP_READINESS_PROBE_TIMEOUT_SECONDS",
            DEFAULT_TMAP_READINESS_PROBE_TIMEOUT_SECONDS,
        )
        if self.tmap_readiness_probe_timeout_seconds > 10:
            raise ValueError("TMAP_READINESS_PROBE_TIMEOUT_SECONDS must be at most 10")
        self.tmap_readiness_success_max_age_seconds = _parse_positive_float(
            "TMAP_READINESS_SUCCESS_MAX_AGE_SECONDS",
            DEFAULT_TMAP_READINESS_SUCCESS_MAX_AGE_SECONDS,
        )
        if self.tmap_readiness_success_max_age_seconds > 3600:
            raise ValueError("TMAP_READINESS_SUCCESS_MAX_AGE_SECONDS must be at most 3600")
        self.tmap_pedestrian_speed_kmh = _parse_positive_float(
            "TMAP_PEDESTRIAN_SPEED_KMH",
            DEFAULT_TMAP_PEDESTRIAN_SPEED_KMH,
        )
        if self.tmap_pedestrian_speed_kmh > MAX_TMAP_PEDESTRIAN_SPEED_KMH:
            raise ValueError(
                "TMAP_PEDESTRIAN_SPEED_KMH must be at most "
                f"{MAX_TMAP_PEDESTRIAN_SPEED_KMH:g}"
            )
        self.max_report_metadata_bytes = _parse_positive_int(
            "MAX_REPORT_METADATA_BYTES",
            DEFAULT_MAX_REPORT_METADATA_BYTES,
        )
        if self.max_report_metadata_bytes > MAX_REPORT_METADATA_BYTES_LIMIT:
            raise ValueError(
                f"MAX_REPORT_METADATA_BYTES must be at most {MAX_REPORT_METADATA_BYTES_LIMIT}"
            )
        self.android_debug_log_dir = Path(
            os.getenv("ANDROID_DEBUG_LOG_DIR", str(backend_root / "android_debug_logs"))
        ).resolve()
        self.android_debug_log_enabled = _parse_bool(
            "ANDROID_DEBUG_LOG_ENABLED",
            DEFAULT_ANDROID_DEBUG_LOG_ENABLED,
        )
        self.max_android_debug_log_bytes = _parse_positive_int(
            "MAX_ANDROID_DEBUG_LOG_BYTES",
            DEFAULT_MAX_ANDROID_DEBUG_LOG_BYTES,
        )
        self.android_debug_log_retention_days = _parse_positive_int(
            "ANDROID_DEBUG_LOG_RETENTION_DAYS",
            DEFAULT_ANDROID_DEBUG_LOG_RETENTION_DAYS,
        )
        if self.android_debug_log_retention_days > 30:
            raise ValueError("ANDROID_DEBUG_LOG_RETENTION_DAYS must be at most 30")
        self.cors_origins = [
            origin.strip()
            for origin in os.getenv("CORS_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000").split(",")
            if origin.strip()
        ]
        self.field_test_security_enabled = _parse_bool(
            "WALKSAFE_FIELD_TEST_SECURITY_ENABLED",
            DEFAULT_FIELD_TEST_SECURITY_ENABLED,
        )
        self.admin_security_enabled = _parse_bool(
            "WALKSAFE_ADMIN_SECURITY_ENABLED",
            DEFAULT_ADMIN_SECURITY_ENABLED,
        )
        self.admin_device_proof_enabled = self.admin_security_enabled
        self.admin_id = _env_text("WALKSAFE_ADMIN_ID")
        self.admin_totp_secret = _env_text("WALKSAFE_ADMIN_TOTP_SECRET").replace(" ", "").upper()
        raw_admin_credential_issuer_key_file = _env_text(
            "WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY_FILE"
        )
        self.admin_session_ttl_seconds = _parse_positive_int(
            "WALKSAFE_ADMIN_SESSION_TTL_SECONDS",
            DEFAULT_ADMIN_SESSION_TTL_SECONDS,
        )
        self.admin_step_up_ttl_seconds = _parse_positive_int(
            "WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS",
            DEFAULT_ADMIN_STEP_UP_TTL_SECONDS,
        )
        self.admin_recovery_ttl_seconds = _parse_positive_int(
            "WALKSAFE_ADMIN_RECOVERY_TTL_SECONDS",
            DEFAULT_ADMIN_RECOVERY_TTL_SECONDS,
        )
        self.admin_auth_rate_limit_attempts = _parse_positive_int(
            "WALKSAFE_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS",
            DEFAULT_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS,
        )
        self.admin_auth_rate_limit_window_seconds = _parse_positive_int(
            "WALKSAFE_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS",
            DEFAULT_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS,
        )
        if self.admin_session_ttl_seconds > 24 * 60 * 60:
            raise ValueError("WALKSAFE_ADMIN_SESSION_TTL_SECONDS must be at most 86400")
        if self.admin_step_up_ttl_seconds > 15 * 60:
            raise ValueError("WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS must be at most 900")
        if self.admin_recovery_ttl_seconds > 30 * 60:
            raise ValueError("WALKSAFE_ADMIN_RECOVERY_TTL_SECONDS must be at most 1800")
        if self.admin_auth_rate_limit_attempts > 20:
            raise ValueError("WALKSAFE_ADMIN_AUTH_RATE_LIMIT_ATTEMPTS must be at most 20")
        if self.admin_auth_rate_limit_window_seconds > 60 * 60:
            raise ValueError("WALKSAFE_ADMIN_AUTH_RATE_LIMIT_WINDOW_SECONDS must be at most 3600")
        self.field_test_token = _env_text("WALKSAFE_FIELD_TEST_TOKEN")
        self.admin_token = _env_text("WALKSAFE_ADMIN_TOKEN")
        self.gateway_session_secret = _env_text("WALKSAFE_GATEWAY_SESSION_SECRET")
        self.privacy_hmac_secret = _env_text("WALKSAFE_PRIVACY_HMAC_SECRET")
        self.privacy_hmac_key_version = _parse_positive_int(
            "WALKSAFE_PRIVACY_HMAC_KEY_VERSION",
            1,
        )
        maintenance_lock_path = _env_text("WALKSAFE_MAINTENANCE_LOCK_PATH")
        raw_maintenance_lock = Path(maintenance_lock_path).expanduser() if maintenance_lock_path else None
        self.maintenance_lock_path = raw_maintenance_lock
        self._maintenance_lock_was_absolute = (
            raw_maintenance_lock.is_absolute() if raw_maintenance_lock is not None else False
        )
        self.walksafe_environment = _env_text("WALKSAFE_ENVIRONMENT", "development").lower()
        self.admin_credential_issuer_key_file = (
            _parse_admin_credential_issuer_key_file(
                raw_admin_credential_issuer_key_file,
                self.walksafe_environment,
            )
        )
        self.backend_workers = _parse_positive_int("WALKSAFE_BACKEND_WORKERS", 1)
        self.backend_replicas = _parse_positive_int("WALKSAFE_BACKEND_REPLICAS", 1)
        self.actor_rate_limit_store = _env_text(
            "WALKSAFE_ACTOR_RATE_LIMIT_STORE",
            "postgresql" if self.walksafe_environment in DEPLOYMENT_ENVIRONMENTS else "memory",
        )
        if self.actor_rate_limit_store not in {"memory", "postgresql"}:
            raise ValueError("WALKSAFE_ACTOR_RATE_LIMIT_STORE must be memory or postgresql")
        default_process_isolation = "false" if self.walksafe_environment == "test" else "true"
        self.inference_process_isolation_enabled = _parse_bool(
            "INFERENCE_PROCESS_ISOLATION_ENABLED",
            default_process_isolation,
        )
        self.inference_timeout_seconds = _parse_positive_float(
            "INFERENCE_TIMEOUT_SECONDS",
            DEFAULT_INFERENCE_TIMEOUT_SECONDS,
        )
        if self.inference_timeout_seconds > 30:
            raise ValueError("INFERENCE_TIMEOUT_SECONDS must be at most 30")
        self.inference_startup_timeout_seconds = _parse_positive_float(
            "INFERENCE_STARTUP_TIMEOUT_SECONDS",
            DEFAULT_INFERENCE_STARTUP_TIMEOUT_SECONDS,
        )
        if self.inference_startup_timeout_seconds > 120:
            raise ValueError("INFERENCE_STARTUP_TIMEOUT_SECONDS must be at most 120")
        if self.inference_startup_timeout_seconds < self.inference_timeout_seconds:
            raise ValueError("INFERENCE_STARTUP_TIMEOUT_SECONDS must be at least INFERENCE_TIMEOUT_SECONDS")
        self.walksafe_source_commit = _env_text("WALKSAFE_SOURCE_COMMIT").lower()
        if self.walksafe_source_commit and FULL_GIT_COMMIT.fullmatch(self.walksafe_source_commit) is None:
            raise ValueError("WALKSAFE_SOURCE_COMMIT must be a full lowercase Git commit SHA")
        if self.walksafe_environment not in SUPPORTED_WALKSAFE_ENVIRONMENTS:
            raise ValueError("WALKSAFE_ENVIRONMENT has an unsupported value")
        self.allow_insecure_local_dev = _parse_bool("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "false")
        if not self.field_test_security_enabled and (
            self.walksafe_environment not in {"development", "test"} or not self.allow_insecure_local_dev
        ):
            raise ValueError(
                "WALKSAFE_FIELD_TEST_SECURITY_ENABLED=false requires an explicit insecure local-development opt-in"
            )
        if self.field_test_security_enabled:
            if len(self.field_test_token) < MIN_FIELD_TEST_TOKEN_LENGTH:
                raise ValueError(
                    f"WALKSAFE_FIELD_TEST_TOKEN must contain at least {MIN_FIELD_TEST_TOKEN_LENGTH} characters"
                )
            if not self.admin_security_enabled and len(self.admin_token) < MIN_FIELD_TEST_TOKEN_LENGTH:
                raise ValueError(
                    f"WALKSAFE_ADMIN_TOKEN must contain at least {MIN_FIELD_TEST_TOKEN_LENGTH} characters"
                )
            if self.admin_token and self.field_test_token == self.admin_token:
                raise ValueError("WALKSAFE_FIELD_TEST_TOKEN and WALKSAFE_ADMIN_TOKEN must differ")
        if self.admin_security_enabled:
            if ADMIN_ID_PATTERN.fullmatch(self.admin_id) is None:
                raise ValueError("WALKSAFE_ADMIN_ID must be a valid named administrator identifier")
            self.admin_totp_secret = validate_admin_totp_secret(
                self.admin_totp_secret
            )
        if self.walksafe_environment in DEPLOYMENT_ENVIRONMENTS:
            if _env_text("WALKSAFE_MIGRATION_DATABASE_URL"):
                raise ValueError(
                    "API deployment must not expose WALKSAFE_MIGRATION_DATABASE_URL"
                )
            _validate_deployment_database_transport(self.database_url)
            if not self.database_at_rest_encryption_confirmed:
                raise ValueError(
                    "deployment environments require confirmed database at-rest encryption"
                )
            if not self.database_transport_security_confirmed:
                raise ValueError(
                    "deployment environments require confirmed database TLS or protected local transport"
                )
            if (
                KEY_BOUNDARY_PATTERN.fullmatch(self.database_encryption_key_boundary) is None
                or KEY_BOUNDARY_PATTERN.fullmatch(self.report_image_key_boundary) is None
                or self.database_encryption_key_boundary == self.report_image_key_boundary
            ):
                raise ValueError(
                    "database and report image encryption must declare distinct non-secret key boundaries"
                )
            if self.actor_rate_limit_store != "postgresql":
                raise ValueError(
                    "deployment environments require WALKSAFE_ACTOR_RATE_LIMIT_STORE=postgresql"
                )
            if len(self.gateway_session_secret) < 32:
                raise ValueError("WALKSAFE_GATEWAY_SESSION_SECRET must contain at least 32 characters")
            if not _has_minimum_privacy_hmac_bytes(self.privacy_hmac_secret):
                raise ValueError(
                    "WALKSAFE_PRIVACY_HMAC_SECRET must contain at least 32 UTF-8 bytes"
                )
            if self.gateway_session_secret in {
                value for value in (self.field_test_token, self.admin_token) if value
            }:
                raise ValueError("WALKSAFE_GATEWAY_SESSION_SECRET must differ from field/admin tokens")
            if self.privacy_hmac_secret in {
                value
                for value in (
                    self.gateway_session_secret,
                    self.field_test_token,
                    self.admin_token,
                )
                if value
            }:
                raise ValueError(
                    "WALKSAFE_PRIVACY_HMAC_SECRET must differ from gateway/field/admin secrets"
                )
            if not self.inference_process_isolation_enabled:
                raise ValueError("INFERENCE_PROCESS_ISOLATION_ENABLED must be true in deployment environments")
            if not self.walksafe_source_commit:
                raise ValueError("WALKSAFE_SOURCE_COMMIT is required in deployment environments")
            if self.android_debug_log_enabled:
                raise ValueError("ANDROID_DEBUG_LOG_ENABLED must be false in deployment environments")
            if not self.tmap_app_key:
                raise ValueError("TMAP_APP_KEY is required in deployment environments")
            if self.tmap_poi_provider != "live":
                raise ValueError("TMAP_POI_PROVIDER must be live in deployment environments")
            if not self._upload_dir_was_absolute:
                raise ValueError("UPLOAD_DIR must be absolute in deployment environments")
            try:
                upload_metadata = self._configured_upload_dir.stat(follow_symlinks=False)
            except OSError as exc:
                raise ValueError("UPLOAD_DIR must exist before deployment startup") from exc
            if (
                self._configured_upload_dir.is_symlink()
                or not self._configured_upload_dir.is_dir()
                or upload_metadata.st_uid != os.geteuid()
                or upload_metadata.st_mode & 0o077
            ):
                raise ValueError(
                    "UPLOAD_DIR must be a service-owned real directory that denies group/other access"
                )
            self._validate_deployment_detector()
            if not self.admin_security_enabled:
                raise ValueError(
                    "WALKSAFE_ADMIN_SECURITY_ENABLED must be true in deployment environments"
                )

    def _validate_deployment_detector(self) -> None:
        if self.detect_v2_mode not in {"real", "yolo"}:
            raise ValueError(
                "DETECT_V2_MODE must use a real detector in field, staging, and production"
            )

        unified_ready = _is_regular_file(self.detect_v2_unified_model_path)
        if unified_ready and self.detect_v2_image_size != 768:
            raise ValueError("DETECT_V2_IMAGE_SIZE must be 768 for the deployed unified checkpoint")
        legacy_ready = _is_regular_file(self.detect_v2_custom_tactile_model_path) and _is_regular_file(
            self.detect_v2_coco_model_path
        )
        if not unified_ready and not legacy_ready:
            raise ValueError(
                "a unified detect-v2 checkpoint or both legacy detect-v2 checkpoints must be configured"
            )
        if not _is_regular_file(self.detect_v2_runtime_config_path):
            raise ValueError("DETECT_V2_RUNTIME_CONFIG_PATH must reference an existing file")
        configured_paths = [
            path
            for path in (
                self.detect_v2_unified_model_path,
                self.detect_v2_custom_tactile_model_path,
                self.detect_v2_coco_model_path,
                self.detect_v2_runtime_config_path,
            )
            if path is not None
        ]
        if any(not path.is_absolute() for path in configured_paths):
            raise ValueError("detect-v2 artifact paths must be absolute in deployment environments")
        try:
            load_threshold_config(self.detect_v2_runtime_config_path)
            _validate_runtime_artifact_bindings(
                self.detect_v2_runtime_config_path,
                unified_model_path=self.detect_v2_unified_model_path if unified_ready else None,
                custom_model_path=self.detect_v2_custom_tactile_model_path if legacy_ready else None,
                coco_model_path=self.detect_v2_coco_model_path if legacy_ready else None,
            )
        except Exception as exc:
            raise ValueError("DETECT_V2_RUNTIME_CONFIG_PATH is invalid") from exc
        if self.maintenance_lock_path is None or not self._maintenance_lock_was_absolute:
            raise ValueError("WALKSAFE_MAINTENANCE_LOCK_PATH must be an absolute path")
        lock_parent = self.maintenance_lock_path.parent
        if not lock_parent.is_dir() or lock_parent.is_symlink():
            raise ValueError("WALKSAFE_MAINTENANCE_LOCK_PATH parent must be a real directory")
        lock_parent_stat = lock_parent.stat()
        if lock_parent_stat.st_uid != os.geteuid():
            raise ValueError("WALKSAFE_MAINTENANCE_LOCK_PATH parent must be owned by the service user")
        if stat.S_IMODE(lock_parent_stat.st_mode) != 0o700:
            raise ValueError("WALKSAFE_MAINTENANCE_LOCK_PATH parent must have mode 0700")
        if self.maintenance_lock_path.is_symlink():
            raise ValueError("WALKSAFE_MAINTENANCE_LOCK_PATH must not be a symlink")
        if self.maintenance_lock_path.exists():
            lock_stat = self.maintenance_lock_path.stat()
            if (
                not stat.S_ISREG(lock_stat.st_mode)
                or lock_stat.st_uid != os.geteuid()
                or stat.S_IMODE(lock_stat.st_mode) != 0o600
                or lock_stat.st_nlink != 1
            ):
                raise ValueError(
                    "WALKSAFE_MAINTENANCE_LOCK_PATH must be a service-owned single-link 0600 regular file"
                )
        if not _trusted_maintenance_lock_parent(lock_parent):
            raise ValueError(
                "WALKSAFE_MAINTENANCE_LOCK_PATH parent must be protected by a root-owned "
                "non-writable authority ancestry up to /"
            )


def _trusted_maintenance_lock_parent(parent: Path) -> bool:
    if os.geteuid() == 0 or not parent.is_absolute():
        return False
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | os.O_DIRECTORY
        | os.O_NOFOLLOW
    )
    authority_paths = [Path("/")]
    authority_descriptors: list[int] = []
    parent_descriptor: int | None = None
    try:
        if parent.resolve(strict=True) != parent:
            return False
        authority_descriptors.append(os.open("/", flags))
        for component in parent.parent.relative_to("/").parts:
            authority_descriptors.append(
                os.open(component, flags, dir_fd=authority_descriptors[-1])
            )
            authority_paths.append(authority_paths[-1] / component)
        for index, (authority_path, descriptor) in enumerate(
            zip(authority_paths, authority_descriptors, strict=True)
        ):
            opened_authority = os.fstat(descriptor)
            path_authority = authority_path.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened_authority.st_mode)
                or opened_authority.st_uid != 0
                or stat.S_IMODE(opened_authority.st_mode) & 0o022
                or os.access(
                    ".",
                    os.W_OK,
                    dir_fd=descriptor,
                    effective_ids=True,
                    follow_symlinks=False,
                )
                or (path_authority.st_dev, path_authority.st_ino)
                != (opened_authority.st_dev, opened_authority.st_ino)
            ):
                return False
            if index:
                anchored_authority = os.stat(
                    authority_path.name,
                    dir_fd=authority_descriptors[index - 1],
                    follow_symlinks=False,
                )
                if (anchored_authority.st_dev, anchored_authority.st_ino) != (
                    opened_authority.st_dev,
                    opened_authority.st_ino,
                ):
                    return False
        parent_descriptor = os.open(parent.name, flags, dir_fd=authority_descriptors[-1])
        opened_parent = os.fstat(parent_descriptor)
        anchored_parent = os.stat(
            parent.name,
            dir_fd=authority_descriptors[-1],
            follow_symlinks=False,
        )
        path_parent = parent.stat(follow_symlinks=False)
        return (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        )
    except (OSError, RuntimeError):
        return False
    finally:
        if parent_descriptor is not None:
            os.close(parent_descriptor)
        for descriptor in reversed(authority_descriptors):
            os.close(descriptor)


def _is_regular_file(path: Path | None) -> bool:
    return path is not None and path.is_file() and not path.is_symlink()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_runtime_artifact_bindings(
    runtime_config_path: Path,
    *,
    unified_model_path: Path | None,
    custom_model_path: Path | None,
    coco_model_path: Path | None,
) -> None:
    if runtime_config_path.suffix.lower() != ".json":
        raise ValueError("deployment runtime config must be JSON")
    raw_config = json.loads(runtime_config_path.read_text(encoding="utf-8"))
    models = raw_config.get("models") if isinstance(raw_config, dict) else None
    if not isinstance(models, dict):
        raise ValueError("runtime config models are missing")
    for model_key, artifact_path in (
        ("unified_walksafe", unified_model_path),
        ("custom_tactile", custom_model_path),
        ("coco_general", coco_model_path),
    ):
        if artifact_path is None:
            continue
        model_config = models.get(model_key)
        declared_digest = model_config.get("artifact_sha256") if isinstance(model_config, dict) else None
        if not isinstance(declared_digest, str) or len(declared_digest) != 64:
            raise ValueError(f"models.{model_key}.artifact_sha256 must be a full SHA-256 digest")
        try:
            int(declared_digest, 16)
        except ValueError as exc:
            raise ValueError(f"models.{model_key}.artifact_sha256 is invalid") from exc
        if _sha256_file(artifact_path) != declared_digest.lower():
            raise ValueError(f"models.{model_key}.artifact_sha256 does not match the configured artifact")


@lru_cache
def get_settings() -> Settings:
    return Settings()
