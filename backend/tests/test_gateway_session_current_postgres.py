"""Opt-in real DB/HTTP integration for Backend-only epoch revocation at Gateway.

Build apps/android-gateway first (default apps/android-gateway/dist, or set
WALKSAFE_GATEWAY_BUILD_DIR to a tsc --outDir tree). A build without the
request-bound v4 actor assertion fails instead of passing on legacy v3.
Run with PYTHON_DOTENV_DISABLED=1 and an
explicitly disposable WALKSAFE_TEST_DATABASE_URL named
walksafe_gateway_epoch_test_*; never use the field database.
Only navigation/report/consent business providers below are controlled fakes.
Account authentication, signature checks and session-current DB reads are real.
"""
from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import socket
import subprocess
import threading
import time
from types import SimpleNamespace
from urllib.parse import urlsplit
import uuid

from alembic import command
from alembic.config import Config
from fastapi import FastAPI, Request
import pytest
from sqlalchemy import create_engine, select, update
from sqlalchemy.orm import Session
from starlette.responses import JSONResponse
import uvicorn

from backend.app.api import accounts
from backend.app.config import get_settings, migration_database_url
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.models import UserAccount
from backend.app.services.accounts import (
    account_email_crypto_for_settings, bind_or_verify_account_crypto_keys,
    hash_account_password,
)

ROOT = Path(__file__).resolve().parents[2]
GATEWAY = ROOT / "apps/android-gateway"
SECRET = "epoch-integration-session-secret-123456789012345678901234"
TOKEN = "epoch-integration-service-token-123456789012345"
PASSWORD = "epoch-integration-password-for-tests"
SIGNED_V4_ASSERTION = re.compile(
    r"v4\.[1-9][0-9]*\.[1-9][0-9]*\.(?:0|[1-9][0-9]*)\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}"
)
requires_epoch_database = pytest.mark.skipif(
    not urlsplit(os.environ.get("WALKSAFE_TEST_DATABASE_URL", "")).path.startswith(
        "/walksafe_gateway_epoch_test_"
    ),
    reason="requires a disposable walksafe_gateway_epoch_test_* PostGIS database",
)


def gateway_build_dir() -> Path:
    """Compiled Gateway tree (src/, test/); default is the npm build output."""
    configured = os.environ.get("WALKSAFE_GATEWAY_BUILD_DIR", "").strip()
    build = Path(configured).expanduser() if configured else GATEWAY / "dist"
    return (build if build.is_absolute() else ROOT / build).resolve()


NODE_BUILD_CHECK = """
const backend = await import(process.env.EPOCH_TEST_BACKEND_MODULE);
console.log(JSON.stringify({
  pending: backend.PENDING_BACKEND_ACTOR_ASSERTION ?? null,
  v4: typeof backend.createBackendActorAssertionV4,
}));
"""


def require_request_bound_gateway_build(build: Path, node: str) -> None:
    """Fail (never skip): a stale build would send v3 and pass falsely."""
    backend_module = build / "src/backend.js"
    if not backend_module.is_file():
        pytest.fail(f"Gateway build is missing {backend_module}; compile apps/android-gateway "
                    "and set WALKSAFE_GATEWAY_BUILD_DIR", pytrace=False)
    result = subprocess.run(
        [node, "--input-type=module", "-e", NODE_BUILD_CHECK], cwd=GATEWAY,
        env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "NODE_ENV": "test",
             "EPOCH_TEST_BACKEND_MODULE": backend_module.as_uri()},
        capture_output=True, text=True, timeout=20,
    )
    observed = json.loads(result.stdout) if result.returncode == 0 else {"error": result.stderr[-500:]}
    if observed != {"pending": "v4.pending", "v4": "function"}:
        pytest.fail(
            f"Gateway build {build} predates request-bound v4 actor assertions "
            f"(PENDING_BACKEND_ACTOR_ASSERTION/createBackendActorAssertionV4 exports: {observed}); "
            "rebuild it (node node_modules/typescript/bin/tsc --outDir <dir>) and set "
            "WALKSAFE_GATEWAY_BUILD_DIR, otherwise this test would exercise a legacy v3 assertion",
            pytrace=False,
        )


def migrate_epoch_database(url):
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", url)
        command.upgrade(Config(str(ROOT / "backend/alembic.ini")), "head")


@pytest.fixture(scope="module")
def epoch_engine():
    assert os.environ.get("PYTHON_DOTENV_DISABLED") == "1", "repository .env loading must be disabled"
    url = os.environ["WALKSAFE_TEST_DATABASE_URL"]
    parsed = urlsplit(url)
    assert parsed.scheme == "postgresql+psycopg"
    assert parsed.hostname == "127.0.0.1" and parsed.port not in (None, 55432)
    assert parsed.path.startswith("/walksafe_gateway_epoch_test_")
    migrate_epoch_database(url)
    engine = create_engine(url)
    try:
        yield engine
    finally:
        engine.dispose()


@pytest.mark.parametrize("ambient_migration_url", [None, "postgresql+psycopg://test:disabled@127.0.0.1:2/other_test_database"])
@pytest.mark.parametrize("migration_fails", [False, True])
def test_epoch_migration_pins_validated_url_and_restores_environment(monkeypatch, ambient_migration_url, migration_fails):
    url = "postgresql+psycopg://test:disabled@127.0.0.1:1/walksafe_gateway_epoch_test_selection"
    monkeypatch.setenv("PYTHON_DOTENV_DISABLED", "1")
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("DATABASE_URL", url)
    if ambient_migration_url is None:
        monkeypatch.delenv("WALKSAFE_MIGRATION_DATABASE_URL", raising=False)
    else:
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", ambient_migration_url)
    selected = []

    def observe_upgrade(config, revision):
        assert config.config_file_name == str(ROOT / "backend/alembic.ini")
        assert revision == "head"
        # Exercise Alembic env.py's real URL selector without opening any DB.
        selected.append(migration_database_url())
        if migration_fails:
            raise RuntimeError("controlled migration failure")

    monkeypatch.setattr(command, "upgrade", observe_upgrade)
    if migration_fails:
        with pytest.raises(RuntimeError, match="controlled migration failure"):
            migrate_epoch_database(url)
    else:
        migrate_epoch_database(url)
    assert selected == [url]
    assert os.environ.get("WALKSAFE_MIGRATION_DATABASE_URL") == ambient_migration_url


def integration_settings():
    values = vars(get_settings()).copy()
    values.update(
        field_test_security_enabled=True, field_test_token=TOKEN,
        admin_token="", gateway_session_secret=SECRET,
        walksafe_environment="test", allow_insecure_local_dev=False,
        actor_rate_limit_store="memory",
    )
    return SimpleNamespace(**values)


def read_phase(process, selector, stderr_path, phase):
    assert selector.select(timeout=20), f"Gateway driver did not produce {phase}; stderr={stderr_path.read_text()}"
    line = process.stdout.readline()
    assert line, f"Gateway driver exited before {phase}; stderr={stderr_path.read_text()}"
    observed = json.loads(line)
    assert observed["phase"] == phase
    print(json.dumps(observed, sort_keys=True))
    return observed


@requires_epoch_database
@pytest.mark.parametrize("version", ["v6", "v7"])
def test_backend_only_epoch_update_blocks_existing_gateway_cookie_over_http(epoch_engine, tmp_path, version):
    settings = integration_settings()
    crypto = account_email_crypto_for_settings(settings)
    actor = str(uuid.uuid4())
    account_id = uuid.uuid4()
    email = f"epoch-{version}-{account_id}@example.org"
    nonce, ciphertext = crypto.encrypt(account_id, email)
    now = datetime.now(timezone.utc)
    # Match Backend startup: bind crypto keys before any account exists.
    with Session(epoch_engine) as db, db.begin():
        bind_or_verify_account_crypto_keys(db, crypto)
    with epoch_engine.begin() as connection:
        connection.execute(UserAccount.__table__.insert().values(
            id=account_id, actor_id=actor,
            privacy_subject_hmac=hashlib.sha256(actor.encode()).hexdigest(),
            email_lookup_hmac=crypto.email_lookup_hmac(email),
            email_ciphertext=ciphertext, email_nonce=nonce,
            email_key_version=crypto.key_version, password_hash=hash_account_password(PASSWORD),
            status="ACTIVE", account_generation=1, auth_epoch=1,
            created_at=now, updated_at=now,
        ))

    business_calls: list[str] = []
    auth_http: list[tuple[str, int]] = []
    app = FastAPI()
    # Real AccountService, real get_db and real session-current implementation.
    # The sender is unused; this fixture never invokes enrollment/email routes.
    app.include_router(accounts.create_router(settings, sender=object()))

    @app.post("/navigation/walking")
    async def controlled_walking(request: Request):
        await request.body()
        business_calls.append("/navigation/walking")
        return JSONResponse({"ok": True})

    @app.post("/reports/v2")
    async def controlled_report(request: Request):
        await request.body()
        business_calls.append("/reports/v2")
        return JSONResponse({"id": "11111111-1111-4111-8111-111111111111"}, status_code=201)

    @app.post("/privacy/consent-events")
    async def controlled_consent_receipt(request: Request):
        body = await request.body()
        payload = json.loads(body)
        return JSONResponse({
            "schema_version": "walksafe.privacy-consent-receipt.v2",
            "request_id": payload["request_id"],
            "client_revision": payload["client_revision"],
            "receipt_sha256": hashlib.sha256(body).hexdigest(),
            "recorded_at": "2026-09-26T00:00:00Z",
        }, status_code=201)

    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)

    actor_assertions: list[tuple[str, bool]] = []

    @app.middleware("http")
    async def observe_auth_http(request: Request, call_next):
        assertion = request.headers.get("x-walksafe-actor-assertion")
        if assertion is not None:
            actor_assertions.append((request.url.path, SIGNED_V4_ASSERTION.fullmatch(assertion) is not None))
        response = await call_next(request)
        if request.url.path in ("/accounts/authenticate", "/accounts/session-current"):
            auth_http.append((request.url.path, response.status_code))
        return response

    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, log_level="error", access_log=False, lifespan="off"))
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    process = None
    selector = selectors.DefaultSelector()
    stderr_path = tmp_path / "gateway-driver.stderr"
    thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(0.01)
        assert server.started, "isolated Backend listener did not start"
        build = gateway_build_dir()
        driver = build / "test/backend-session-current-integration-driver.js"
        assert driver.is_file(), (
            f"missing {driver}; build apps/android-gateway (npm run build, or tsc --outDir "
            "with WALKSAFE_GATEWAY_BUILD_DIR) first"
        )
        node = shutil.which("node")
        assert node is not None
        require_request_bound_gateway_build(build, node)
        state = tmp_path / "gateway-state"
        state.mkdir(mode=0o700)
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "NODE_ENV": "test", "WALKSAFE_ENVIRONMENT": "test",
            "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
            "WALKSAFE_FIELD_TEST_TOKEN": TOKEN, "WALKSAFE_GATEWAY_SESSION_SECRET": SECRET,
            "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "cf-connecting-ip",
            "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(state),
            "WALKSAFE_FIELD_WALK_LEDGER_PATH": str(state / "walk.json"),
            "EPOCH_TEST_BACKEND_ORIGIN": f"http://127.0.0.1:{port}",
            "EPOCH_TEST_COOKIE_VERSION": version, "EPOCH_TEST_EMAIL": email,
            "EPOCH_TEST_PASSWORD": PASSWORD,
        }
        with stderr_path.open("w") as stderr:
            process = subprocess.Popen(
                [node, str(driver)], cwd=GATEWAY, env=env, text=True,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
            )
            selector.register(process.stdout, selectors.EVENT_READ)
            before = read_phase(process, selector, stderr_path, "before")
            assert before["statuses"] == [200, 201]
            assert before["local_epoch"] == 1
            assert business_calls == ["/navigation/walking", "/reports/v2"]
            assert auth_http == [
                ("/accounts/authenticate", 200), ("/accounts/session-current", 204),
                ("/accounts/session-current", 204), ("/accounts/session-current", 204),
            ]

            # Change Backend DB only: no Gateway session/ledger/cookie mutation.
            with epoch_engine.begin() as connection:
                result = connection.execute(update(UserAccount).where(UserAccount.id == account_id).values(auth_epoch=2))
                assert result.rowcount == 1
                stored = connection.execute(select(
                    UserAccount.account_generation, UserAccount.auth_epoch, UserAccount.status,
                ).where(UserAccount.id == account_id)).one()
                assert tuple(stored) == (1, 2, "ACTIVE")
            process.stdin.write("epoch-updated\n")
            process.stdin.flush()
            stale = read_phase(process, selector, stderr_path, "stale")
            assert stale["statuses"] == [401, 401]
            assert stale["local_epoch"] == 1
            assert business_calls == ["/navigation/walking", "/reports/v2"]
            assert auth_http[-2:] == [("/accounts/session-current", 401)] * 2

            process.stdin.write("stale-observed\n")
            process.stdin.flush()
            refreshed = read_phase(process, selector, stderr_path, "refreshed")
            assert refreshed["statuses"] == [200, 201]
            assert refreshed["local_epoch"] == 2
            assert business_calls == ["/navigation/walking", "/reports/v2"] * 2
            assert auth_http[-3:] == [
                ("/accounts/authenticate", 200), ("/accounts/session-current", 204),
                ("/accounts/session-current", 204),
            ]
            assert process.wait(timeout=10) == 0, stderr_path.read_text()
            # Every actor-bound Backend call carried a signed request-bound v4 assertion.
            assert {path for path, _ in actor_assertions} >= {
                "/accounts/session-current", "/navigation/walking", "/reports/v2",
            }
            assert all(signed for _, signed in actor_assertions), actor_assertions
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        if process is not None:
            process.stdin.close()
            process.stdout.close()
        selector.close()
        server.should_exit = True
        thread.join(timeout=10)
        listener.close()
        assert not thread.is_alive(), "isolated Backend listener failed to stop"
