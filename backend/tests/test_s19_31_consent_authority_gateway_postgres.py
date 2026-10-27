"""Opt-in real Backend↔Gateway HTTP check of same-account consent freshness.

Run against an isolated, migrated PostGIS test DB with both
WALKSAFE_TEST_DATABASE_URL and WALKSAFE_S19_33_RUNTIME_DATABASE_URL set.
The Gateway driver must be compiled to WALKSAFE_GATEWAY_BUILD_DIR first.
"""

from __future__ import annotations

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import socket
import stat
import subprocess
import threading
import time
from types import SimpleNamespace
import uuid
import xml.etree.ElementTree as ET

from fastapi import FastAPI, Request
import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session
import uvicorn

from backend.app.api import accounts, privacy
from backend.app.config import get_settings
from backend.app.database import get_db
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.models import PrivacyConsentEvent, UserAccount
from backend.app.services.accounts import (
    account_email_crypto_for_settings, bind_or_verify_account_crypto_keys,
    hash_account_password,
)
from backend.app.services.privacy_lifecycle import (
    assert_privacy_runtime_database_role, privacy_subject_hmac,
)


ROOT = Path(__file__).resolve().parents[2]
GATEWAY = ROOT / "apps/android-gateway"
TOKEN = "s19-31-field-test-token-123456789012345678901234"
SESSION_SECRET = "s19-31-session-secret-123456789012345678901234"
PASSWORD = "s19-31-dedicated-integration-password"
SIGNED_V4_ASSERTION = re.compile(
    r"v4\.[1-9][0-9]*\.[1-9][0-9]*\.(?:0|[1-9][0-9]*)\."
    r"[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}"
)


def _settings():
    values = vars(get_settings()).copy()
    values.update(
        field_test_security_enabled=True, field_test_token=TOKEN,
        admin_token="", gateway_session_secret=SESSION_SECRET,
        walksafe_environment="test", allow_insecure_local_dev=False,
        actor_rate_limit_store="memory",
    )
    return SimpleNamespace(**values)


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip()
    or not os.environ.get("WALKSAFE_S19_33_RUNTIME_DATABASE_URL", "").strip(),
    reason="requires explicit dedicated owner and runtime PostGIS test URLs",
)
def test_same_account_two_installations_refresh_backend_authority_over_http(tmp_path):
    assert os.environ.get("PYTHON_DOTENV_DISABLED") == "1"
    owner_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"])
    runtime_url = make_url(os.environ["WALKSAFE_S19_33_RUNTIME_DATABASE_URL"])
    assert owner_url.drivername == runtime_url.drivername == "postgresql+psycopg"
    assert owner_url.host == runtime_url.host == "127.0.0.1"
    assert owner_url.port == runtime_url.port and owner_url.port not in (None, 55432)
    assert owner_url.database == runtime_url.database == "walksafe_s19_33_test"
    assert owner_url.username != runtime_url.username

    build_dir = Path(os.environ["WALKSAFE_GATEWAY_BUILD_DIR"]).resolve()
    assert build_dir != (GATEWAY / "dist").resolve()
    driver = build_dir / "test/consent-authority-integration-driver.js"
    assert driver.is_file(), "compile the Gateway driver into the isolated build directory"
    node = shutil.which("node")
    assert node is not None

    owner_engine = create_engine(owner_url, pool_pre_ping=True)
    runtime_engine = create_engine(runtime_url, pool_pre_ping=True)
    settings = _settings()
    actor = str(uuid.uuid4())
    account_id = uuid.uuid4()
    email = f"s19-31-{account_id}@example.org"
    subject = privacy_subject_hmac(actor, 1, settings.privacy_hmac_secret)
    crypto = account_email_crypto_for_settings(settings)
    nonce, ciphertext = crypto.encrypt(account_id, email)
    now = datetime.now(timezone.utc)
    server = None
    thread = None
    listener = None
    try:
        with Session(runtime_engine) as db:
            assert_privacy_runtime_database_role(db)
            runtime_user = db.scalar(text("SELECT current_user"))
            assert runtime_user == runtime_url.username
            print(f"s19-31 runtime_login={runtime_user} role_guard=PASS")
        with Session(owner_engine) as db, db.begin():
            bind_or_verify_account_crypto_keys(db, crypto)
        with owner_engine.begin() as connection:
            connection.execute(UserAccount.__table__.insert().values(
                id=account_id, actor_id=actor,
                privacy_subject_hmac=subject,
                email_lookup_hmac=crypto.email_lookup_hmac(email),
                email_ciphertext=ciphertext, email_nonce=nonce,
                email_key_version=crypto.key_version,
                password_hash=hash_account_password(PASSWORD),
                status="ACTIVE", account_generation=1, auth_epoch=1,
                created_at=now, updated_at=now,
            ))

        async def runtime_db():
            with Session(runtime_engine) as db:
                yield db

        app = FastAPI()
        app.dependency_overrides[get_db] = runtime_db
        app.include_router(accounts.create_router(settings, sender=object()))
        app.include_router(privacy.create_router(settings))
        app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
        observed: list[tuple[str, int, bool]] = []

        @app.middleware("http")
        async def observe(request: Request, call_next):
            response = await call_next(request)
            assertion = request.headers.get("x-walksafe-actor-assertion")
            observed.append((
                request.url.path, response.status_code,
                assertion is not None and SIGNED_V4_ASSERTION.fullmatch(assertion) is not None,
            ))
            return response

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_level="error", access_log=False, lifespan="off"))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(0.01)
        assert server.started, "isolated Backend listener did not start"

        state = tmp_path / "gateway-state"
        state.mkdir(mode=0o700)
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "NODE_ENV": "test", "WALKSAFE_ENVIRONMENT": "test",
            "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
            "WALKSAFE_FIELD_TEST_TOKEN": TOKEN,
            "WALKSAFE_GATEWAY_SESSION_SECRET": SESSION_SECRET,
            "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "cf-connecting-ip",
            "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(state),
            "WALKSAFE_FIELD_WALK_LEDGER_PATH": str(state / "walk.json"),
            "S19_31_BACKEND_ORIGIN": f"http://127.0.0.1:{port}",
            "S19_31_EMAIL": email, "S19_31_PASSWORD": PASSWORD,
        }
        result = subprocess.run(
            [node, str(driver)], cwd=GATEWAY, env=env,
            capture_output=True, text=True, timeout=50,
        )
        assert result.returncode == 0, result.stderr[-2000:]
        observed_driver = json.loads(result.stdout.strip())
        assert observed_driver["phase"] == "done"
        assert observed_driver["same_choice_get"] == 200
        assert observed_driver["same_choice_extra_put"] == 0
        assert observed_driver["changed_choice_get"] == 409
        assert observed_driver["bootstrap_source"] == "CURRENT_CONSENT"
        assert observed_driver["reconfirmed_get"] == 200
        assert observed_driver["consent_event_posts"] == 4
        assert set(observed_driver["backend_paths"]) == {
            "/accounts/authenticate", "/accounts/session-current",
            "/privacy/consent-events", "/privacy/consent-bootstrap",
        }
        with Session(runtime_engine) as db:
            account = db.scalar(select(UserAccount).where(UserAccount.id == account_id))
            assert (account.actor_id, account.account_generation, account.auth_epoch) == (actor, 1, 1)
            events = db.scalars(select(PrivacyConsentEvent).where(
                PrivacyConsentEvent.privacy_subject_hmac == subject,
            ).order_by(PrivacyConsentEvent.subject_revision)).all()
            assert len(events) == 4
            assert [row.subject_revision for row in events] == [1, 2, 3, 4]
            assert [row.raw_source_collection for row in events] == [True, True, False, True]
            assert len({row.installation_subject_hmac for row in events}) == 2
        assert [status for path, status, _ in observed if path == "/accounts/authenticate"] == [200, 200]
        assert [status for path, status, _ in observed if path == "/privacy/consent-events"] == [201] * 4
        assert all(status == 200 for path, status, _ in observed if path == "/privacy/consent-bootstrap")
        assert all(status == 204 for path, status, _ in observed if path == "/accounts/session-current")
        signed_paths = {
            "/accounts/session-current", "/privacy/consent-bootstrap", "/privacy/consent-events",
        }
        assert signed_paths <= {path for path, _, _ in observed}
        assert all(signed for path, _, signed in observed if path in signed_paths)
        print(json.dumps(observed_driver, sort_keys=True))
    finally:
        if server is not None:
            server.should_exit = True
        if thread is not None:
            thread.join(timeout=10)
            assert not thread.is_alive(), "isolated Backend listener failed to stop"
        if listener is not None:
            listener.close()
        runtime_engine.dispose()
        owner_engine.dispose()


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_S19_31_ANDROID_HANDOFF_DIR", "").strip(),
    reason="opt-in Android JVM handoff profile",
)
def test_android_cas_recovery_against_real_backend_and_gateway(tmp_path):
    """Keep the real loopback stack alive for Root's separately run JVM test.

    Root starts this one pytest case, waits for ready.json, runs the JVM case with
    WALKSAFE_CAS_FIXTURE_JSON, then writes done.json with its Gradle/JUnit evidence.
    A missing or failed JVM result never counts as a passing integration test.
    """
    assert os.environ.get("PYTHON_DOTENV_DISABLED") == "1"
    owner_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"])
    runtime_url = make_url(os.environ["WALKSAFE_S19_33_RUNTIME_DATABASE_URL"])
    assert owner_url.drivername == runtime_url.drivername == "postgresql+psycopg"
    assert owner_url.host == runtime_url.host == "127.0.0.1"
    assert owner_url.port == runtime_url.port and owner_url.port not in (None, 55432)
    assert owner_url.database == runtime_url.database == "walksafe_s19_33_test"
    assert owner_url.username != runtime_url.username
    requested_handoff_dir = Path(os.environ["WALKSAFE_S19_31_ANDROID_HANDOFF_DIR"])
    assert not requested_handoff_dir.is_symlink()
    handoff_dir = requested_handoff_dir.resolve()
    assert handoff_dir.is_dir()
    assert stat.S_IMODE(handoff_dir.stat().st_mode) == 0o700
    fixture_path = handoff_dir / "fixture.json"
    ready_path = handoff_dir / "ready.json"
    done_path = handoff_dir / "done.json"
    result_path = handoff_dir / "results.json"
    assert not any(path.exists() for path in (fixture_path, ready_path, done_path, result_path))
    build_dir = Path(os.environ["WALKSAFE_GATEWAY_BUILD_DIR"]).resolve()
    assert build_dir != (GATEWAY / "dist").resolve()
    driver = build_dir / "test/consent-authority-integration-driver.js"
    assert driver.is_file()
    node = shutil.which("node")
    assert node is not None

    owner_engine = create_engine(owner_url, pool_pre_ping=True)
    runtime_engine = create_engine(runtime_url, pool_pre_ping=True)
    settings = _settings()
    actor = str(uuid.uuid4())
    account_id = uuid.uuid4()
    email = f"s19-31-cas-{account_id}@example.org"
    subject = privacy_subject_hmac(actor, 1, settings.privacy_hmac_secret)
    crypto = account_email_crypto_for_settings(settings)
    nonce, ciphertext = crypto.encrypt(account_id, email)
    now = datetime.now(timezone.utc)
    server = None
    thread = None
    listener = None
    process = None
    selector = selectors.DefaultSelector()
    stderr_file = None
    try:
        with Session(runtime_engine) as db:
            assert_privacy_runtime_database_role(db)
            assert db.scalar(text("SELECT current_user")) == runtime_url.username
        with Session(owner_engine) as db, db.begin():
            bind_or_verify_account_crypto_keys(db, crypto)
        with owner_engine.begin() as connection:
            connection.execute(UserAccount.__table__.insert().values(
                id=account_id, actor_id=actor, privacy_subject_hmac=subject,
                email_lookup_hmac=crypto.email_lookup_hmac(email),
                email_ciphertext=ciphertext, email_nonce=nonce,
                email_key_version=crypto.key_version,
                password_hash=hash_account_password(PASSWORD),
                status="ACTIVE", account_generation=1, auth_epoch=1,
                created_at=now, updated_at=now,
            ))

        async def runtime_db():
            with Session(runtime_engine) as db:
                yield db

        app = FastAPI()
        app.dependency_overrides[get_db] = runtime_db
        app.include_router(accounts.create_router(settings, sender=object()))
        app.include_router(privacy.create_router(settings))
        app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
        observed: list[tuple[str, int, bool]] = []

        @app.middleware("http")
        async def observe(request: Request, call_next):
            response = await call_next(request)
            assertion = request.headers.get("x-walksafe-actor-assertion")
            observed.append((
                request.url.path, response.status_code,
                assertion is not None and SIGNED_V4_ASSERTION.fullmatch(assertion) is not None,
            ))
            return response

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        backend_port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_level="error", access_log=False, lifespan="off"))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(0.01)
        assert server.started, "isolated Backend listener did not start"

        state = tmp_path / "gateway-state"
        state.mkdir(mode=0o700)
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "NODE_ENV": "test", "WALKSAFE_ENVIRONMENT": "test",
            "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
            "WALKSAFE_FIELD_TEST_TOKEN": TOKEN,
            "WALKSAFE_GATEWAY_SESSION_SECRET": SESSION_SECRET,
            "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "cf-connecting-ip",
            "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(state),
            "WALKSAFE_FIELD_WALK_LEDGER_PATH": str(state / "walk.json"),
            "S19_31_BACKEND_ORIGIN": f"http://127.0.0.1:{backend_port}",
            "S19_31_SERVER_ONLY": "1",
        }
        stderr_fd = os.open(state / "gateway.stderr", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        stderr_file = os.fdopen(stderr_fd, "w", encoding="utf-8")
        process = subprocess.Popen(
            [node, str(driver)], cwd=GATEWAY, env=env, text=True,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr_file,
        )
        selector.register(process.stdout, selectors.EVENT_READ)
        assert selector.select(timeout=15), "isolated Gateway did not announce readiness"
        ready = json.loads(process.stdout.readline())
        assert ready.get("phase") == "ready"
        gateway_url = ready.get("gateway_url")
        assert isinstance(gateway_url, str) and gateway_url.startswith("http://127.0.0.1:")
        assert process.poll() is None

        def write_private_json(path: Path, value: dict) -> None:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as output:
                json.dump(value, output, separators=(",", ":"))

        write_private_json(fixture_path, {
            "gateway_url": gateway_url,
            "email": email,
            "password": PASSWORD,
            "installation_a": "s19-31-cas-install-a",
            "installation_b": "s19-31-cas-install-b",
            "control_secret_a": "a" * 64,
            "control_secret_b": "b" * 64,
            "result_path": str(result_path),
        })
        write_private_json(ready_path, {"fixture_path": str(fixture_path), "done_path": str(done_path)})
        ready_mtime_ns = ready_path.stat().st_mtime_ns
        print(f"s19-31 fixture_ready={ready_path}", flush=True)
        handoff_wait_seconds = int(os.environ.get("WALKSAFE_S19_31_ANDROID_HANDOFF_TIMEOUT_SECONDS", "300"))
        assert 1 <= handoff_wait_seconds <= 600
        deadline = time.monotonic() + handoff_wait_seconds
        while not done_path.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.2)
        assert process.poll() is None, "isolated Gateway exited during Android JVM test"
        assert done_path.is_file(), f"Android JVM test did not deliver a done marker within {handoff_wait_seconds} seconds"
        done = json.loads(done_path.read_text(encoding="utf-8"))
        assert done.get("gradle_exit_code") == 0
        xml_path = Path(done["junit_xml"]).resolve()
        assert xml_path.is_file() and xml_path.suffix == ".xml"
        assert xml_path.stat().st_mtime_ns >= ready_mtime_ns, "JUnit evidence predates live fixture"
        xml = ET.parse(xml_path).getroot()
        cases = list(xml.iter("testcase"))
        junit_case = done["junit_case"]
        assert isinstance(junit_case, str) and junit_case
        assert len([case for case in cases if case.get("name") == junit_case]) == 1
        assert all(
            not list(case.iter("failure")) and not list(case.iter("error")) and
            not list(case.iter("skipped"))
            for case in cases if case.get("name") == junit_case
        )
        assert result_path.is_file() and stat.S_IMODE(result_path.stat().st_mode) == 0o600
        assert result_path.stat().st_mtime_ns >= ready_mtime_ns
        android = json.loads(result_path.read_text(encoding="utf-8"))
        old_id = android["original_request_id"]
        successor_id = android["successor_request_id"]
        assert old_id != successor_id
        assert android["cas_status"] == 409
        assert android["cas_code"] == "privacy_consent_previous_receipt_conflict"
        assert android["replay_status"] == 200
        assert android["final_get_status"] == 200
        assert android["final_get_receipt_sha256"] == android["successor_receipt_sha256"]
        assert android["original_journal_preserved"] is True
        assert android["fresh_session_restored"] is True
        assert android["bootstrap_receipt_sha256"] != android["successor_receipt_sha256"]
        with Session(runtime_engine) as db:
            events = db.scalars(select(PrivacyConsentEvent).where(
                PrivacyConsentEvent.privacy_subject_hmac == subject,
            ).order_by(PrivacyConsentEvent.subject_revision)).all()
            assert len(events) == 3, "A initial, B latest, A successor must be the only committed events"
            assert [row.subject_revision for row in events] == [1, 2, 3]
            assert old_id not in {row.request_id for row in events}
            assert events[-1].request_id == successor_id
            assert events[-2].receipt_sha256 == android["bootstrap_receipt_sha256"]
            assert events[-1].receipt_sha256 == android["successor_receipt_sha256"]
            assert events[-1].client_revision == android["successor_client_revision"]
            assert events[-1].client_revision == android["bootstrap_client_revision_floor"] + 1
            latest = events[-2]
            successor = events[-1]
            keys = ("raw_source_collection", "automatic_reporting", "mobile_network_transfer", "training_reuse")
            assert {key: getattr(latest, key) for key in keys} == android["bootstrap_selections"]
            assert {key: getattr(successor, key) for key in keys} == android["successor_selections"]
            assert not latest.raw_source_collection and not successor.raw_source_collection
            assert all(not getattr(successor, key) or getattr(latest, key) for key in keys)
            assert android["withdrawal_items"]
            assert set(android["withdrawal_items"]) <= set(keys)
            assert all(getattr(successor, item) is False for item in android["withdrawal_items"])
        assert [status for path, status, _ in observed if path == "/privacy/consent-events"] == [
            201, 201, 409, 201, 200,
        ]
        signed_paths = {
            "/accounts/session-current", "/privacy/consent-bootstrap", "/privacy/consent-events",
        }
        assert signed_paths <= {path for path, _, _ in observed}
        assert all(signed for path, _, signed in observed if path in signed_paths)
        print(json.dumps({
            "phase": "done", "android_junit_case": junit_case,
            "android_cases": len(cases), "backend_consent_events": len(events),
            "cas_status": 409, "replay_status": 200, "final_get_status": 200,
        }, sort_keys=True))
    finally:
        selector.close()
        if process is not None:
            if process.poll() is None:
                try:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
                    process.wait(timeout=5)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()
        if stderr_file is not None:
            stderr_file.close()
        if server is not None:
            server.should_exit = True
        if thread is not None:
            thread.join(timeout=10)
            assert not thread.is_alive(), "isolated Backend listener failed to stop"
        if listener is not None:
            listener.close()
        runtime_engine.dispose()
        owner_engine.dispose()
        fixture_path.unlink(missing_ok=True)
