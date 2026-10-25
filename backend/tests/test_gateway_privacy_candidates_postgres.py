"""Real isolated HTTP/PG chain for the account and installation privacy contracts."""

import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
from types import SimpleNamespace
import uuid

from fastapi import FastAPI
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
import uvicorn

from backend.app import database
from backend.app.api import accounts, privacy
from backend.app.config import get_settings
from backend.app.database import get_db
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.services import accounts as account_services
from test_gateway_session_current_postgres import (
    ROOT, SECRET, TOKEN, gateway_build_dir, require_request_bound_gateway_build,
)

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL"), reason="isolated PostgreSQL required",
)


def test_gateway_account_and_signed_installation_privacy_chain(monkeypatch, tmp_path):
    build = gateway_build_dir()
    driver = build / "test/backend-privacy-candidates-integration-driver.js"
    assert driver.is_file(), "compile the current Gateway integration driver before this test"
    node = shutil.which("node")
    assert node is not None
    require_request_bound_gateway_build(build, node)
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"], pool_pre_ping=True)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    monkeypatch.setattr(database, "SessionLocal", factory)
    # This delivery boundary is controlled; SMTP timing has its own real TLS tests.
    monkeypatch.setattr(account_services.secrets, "randbelow", lambda _: 123456)
    deliveries = []

    class SyntheticSender:
        def send(self, *, email, code, expires_in_seconds):
            assert code == "123456"
            deliveries.append((email, code, expires_in_seconds))

    settings = SimpleNamespace(**{
        **vars(get_settings()),
        "field_test_security_enabled": True, "field_test_token": TOKEN,
        "gateway_session_secret": SECRET, "walksafe_environment": "test",
        "allow_insecure_local_dev": False, "actor_rate_limit_store": "memory",
        "actor_assertion_v3_policy": "forbid",
    })
    app = FastAPI()
    app.include_router(accounts.create_router(settings, sender=SyntheticSender()))
    app.include_router(privacy.create_router(settings))

    def db_dependency():
        with factory() as db:
            yield db

    app.dependency_overrides[get_db] = db_dependency
    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, log_level="error", lifespan="off"))
    thread = threading.Thread(target=lambda: server.run(sockets=[listener]), daemon=True)
    thread.start()
    state = tmp_path / "gateway-state"
    state.mkdir(mode=0o700)
    log = tmp_path / "gateway-driver.stderr"
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "NODE_ENV": "test", "WALKSAFE_ENVIRONMENT": "test",
        "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
        "WALKSAFE_FIELD_TEST_TOKEN": TOKEN, "WALKSAFE_GATEWAY_SESSION_SECRET": SECRET,
        "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER": "cf-connecting-ip",
        "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(state),
        "WALKSAFE_FIELD_WALK_LEDGER_PATH": str(state / "walk.json"),
        "CANDIDATE_TEST_BACKEND_ORIGIN": f"http://127.0.0.1:{port}",
        "CANDIDATE_TEST_EMAIL": f"candidate-{uuid.uuid4()}@example.org",
        "CANDIDATE_TEST_PASSWORD": "candidate-synthetic-password-20261011",
        "CANDIDATE_TEST_OTP": "123456",
    }
    process = None
    try:
        startup = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < startup:
            time.sleep(0.01)
        assert server.started, "isolated Backend did not start"
        with log.open("w") as stderr:
            process = subprocess.Popen(
                [node, str(driver)], cwd=ROOT / "apps/android-gateway", env=env,
                text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr,
            )
            output, _ = process.communicate(timeout=50)
        assert process.returncode == 0, log.read_text()
        events = [json.loads(line) for line in output.splitlines() if line.strip()]
        assert events and events[-1]["phase"] == "completed", events
        assert len(deliveries) == 1
        # Driver output is limited to statuses and public observations.
        print(json.dumps(events[-1], sort_keys=True))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            process.wait(timeout=5)
        server.should_exit = True
        thread.join(timeout=10)
        listener.close()
        engine.dispose()
        assert not thread.is_alive(), "isolated Backend listener did not stop"
