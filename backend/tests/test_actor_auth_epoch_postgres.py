from __future__ import annotations

import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
from urllib.parse import urlsplit
import uuid

from alembic import command
from alembic.config import Config
import httpx
import pytest
from fastapi import FastAPI, Request
from sqlalchemy import create_engine, update
from starlette.responses import JSONResponse

from backend.app.field_test_security import (
    FieldTestAccess, FieldTestSecurityMiddleware, create_actor_assertion,
)
from backend.app.models import UserAccount

ROOT = Path(__file__).resolve().parents[2]
SECRET = "auth-epoch-contract-secret-123456789012345678901234"
TOKEN = "epoch-internal-token-12345678901234567890"
REQUEST_BODY = b"{}"
pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="requires an explicitly isolated test database",
)


def gateway_build_dir() -> Path:
    """Compiled Gateway tree (src/, test/); default is the npm build output."""
    configured = os.environ.get("WALKSAFE_GATEWAY_BUILD_DIR", "").strip()
    build = Path(configured).expanduser() if configured else ROOT / "apps/android-gateway/dist"
    return (build if build.is_absolute() else ROOT / build).resolve()


NODE_BUILD_CHECK = """
const backend = await import(process.env.EPOCH_TEST_BACKEND_MODULE);
console.log(JSON.stringify({
  pending: backend.PENDING_BACKEND_ACTOR_ASSERTION ?? null,
  v4: typeof backend.createBackendActorAssertionV4,
}));
"""


def require_request_bound_gateway_build(build: Path) -> None:
    """Fail (never skip): a stale build would test v3 and pass falsely."""
    backend_module = build / "src/backend.js"
    if not backend_module.is_file():
        pytest.fail(f"Gateway build is missing {backend_module}; compile apps/android-gateway "
                    "and set WALKSAFE_GATEWAY_BUILD_DIR", pytrace=False)
    result = subprocess.run(
        ["/usr/bin/node", "--input-type=module", "-e", NODE_BUILD_CHECK],
        cwd=ROOT / "apps/android-gateway",
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


# The Gateway signs through fetchBackend, its single v4 signing point. The
# capture transport records exactly what would be sent (method, URL, headers,
# body bytes) and the Backend then receives those same bytes.
NODE_SESSION = """
import path from "node:path";
import { pathToFileURL } from "node:url";
const load = relative => import(pathToFileURL(path.join(process.env.EPOCH_TEST_GATEWAY_BUILD, relative)).href);
const { establishBackendGatewaySession, gatewaySessionAuthEpoch } = await load("src/auth.js");
const { PENDING_BACKEND_ACTOR_ASSERTION, fetchBackend, proxyRequestHeaders } = await load("src/backend.js");
const { configureTestStateEncryption } = await load("test/state-encryption-fixture.js");
globalThis.fetch = async () => { throw new Error("auth-epoch fixture must not perform HTTP requests"); };
await configureTestStateEncryption(path.join(process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR, "keyring.json"));
const input = JSON.parse(process.env.EPOCH_TEST_INPUT);
let cookie = input.cookie;
if (!cookie) {
  const response = establishBackendGatewaySession(
    new Request("https://gateway.invalid/api/field-session"),
    { actorId: input.actor, accountGeneration: 1, authEpoch: input.epoch,
      ...(input.device ? { deviceId: input.device } : {}) },
    false
  );
  if (response.status !== 200) throw new Error("test session was not issued");
  cookie = response.headers.get("set-cookie").split(";", 1)[0];
}
const request = new Request("https://gateway.invalid/api/navigation/walking", { headers: { cookie } });
const requests = {};
for (const target of input.targets) {
  const sent = [];
  const capture = async (url, init) => {
    sent.push({ url: String(url), init });
    return new Response(null, { status: 204 });
  };
  const response = await fetchBackend(request, `http://127.0.0.1:8000${target}`, {
    method: "POST",
    headers: proxyRequestHeaders(request, { "content-type": "application/json" }),
    body: input.body
  }, 5000, capture);
  if (response.status !== 204 || sent.length !== 1) throw new Error("fetchBackend did not send exactly once");
  const headers = Object.fromEntries(new Headers(sent[0].init.headers));
  if (headers["x-walksafe-actor-assertion"] === PENDING_BACKEND_ACTOR_ASSERTION) {
    throw new Error("fetchBackend sent the unsigned v4 placeholder");
  }
  requests[target] = {
    url: sent[0].url, method: sent[0].init.method, headers,
    body_base64: Buffer.from(await new Response(sent[0].init.body).arrayBuffer()).toString("base64")
  };
}
console.log(JSON.stringify({ cookie, epoch: gatewaySessionAuthEpoch(request), requests }));
"""


@pytest.fixture(scope="module")
def isolated_engine():
    url = os.environ["WALKSAFE_TEST_DATABASE_URL"]
    parsed = urlsplit(url)
    assert parsed.hostname in {"127.0.0.1", "localhost"} and parsed.port != 55432
    assert "test" in parsed.path.lower()
    command.upgrade(Config(str(ROOT / "backend/alembic.ini")), "head")
    engine = create_engine(url)
    try:
        yield engine
    finally:
        engine.dispose()


@pytest.fixture(scope="module")
def gateway_build():
    build = gateway_build_dir()
    require_request_bound_gateway_build(build)
    return build


def session_headers(build, directory, actor, *, targets, epoch=1, device=None, cookie=None):
    env = os.environ.copy()
    env.update({
        "NODE_ENV": "test", "WALKSAFE_ENVIRONMENT": "test",
        "WALKSAFE_GATEWAY_RATE_LIMIT_DIR": str(directory),
        "WALKSAFE_FIELD_WALK_LEDGER_PATH": str(directory / "walk.json"),
        "WALKSAFE_GATEWAY_SESSION_SECRET": SECRET,
        "WALKSAFE_FIELD_TEST_TOKEN": TOKEN,
        "BACKEND_API_BASE_URL": "http://127.0.0.1:8000",
        "EPOCH_TEST_GATEWAY_BUILD": str(build),
        "EPOCH_TEST_INPUT": json.dumps({
            "actor": actor, "epoch": epoch, "device": device, "cookie": cookie,
            "targets": list(targets), "body": REQUEST_BODY.decode(),
        }),
    })
    env.pop("WALKSAFE_FIELD_ACCOUNTS_JSON", None)
    result = subprocess.run(
        ["/usr/bin/node", "--input-type=module", "-e", NODE_SESSION],
        cwd=ROOT / "apps/android-gateway", env=env,
        capture_output=True, text=True, timeout=20, check=True,
    )
    return json.loads(result.stdout)


@pytest.mark.parametrize("device", [None, "epoch-postgres-device"], ids=["v6", "v7"])
def test_existing_gateway_cookie_cannot_survive_backend_only_epoch_change(
    isolated_engine, gateway_build, tmp_path, device,
):
    actor = str(uuid.uuid4())
    account_id = uuid.uuid4()
    now = datetime.now(timezone.utc)
    with isolated_engine.begin() as connection:
        connection.execute(UserAccount.__table__.insert().values(
            id=account_id, actor_id=actor,
            privacy_subject_hmac=hashlib.sha256(("subject:" + actor).encode()).hexdigest(),
            email_lookup_hmac=hashlib.sha256(("email:" + actor).encode()).hexdigest(),
            email_ciphertext=b"c" * 17, email_nonce=b"n" * 12, email_key_version=1,
            password_hash="p" * 32, status="ACTIVE",
            account_generation=1, auth_epoch=1, created_at=now, updated_at=now,
        ))
    settings = SimpleNamespace(
        field_test_security_enabled=True, field_test_token=TOKEN, admin_token="",
        gateway_session_secret=SECRET, walksafe_environment="test",
        allow_insecure_local_dev=False, actor_rate_limit_store="memory",
        max_upload_bytes=1_048_576, max_report_metadata_bytes=65_536,
        actor_assertion_v3_policy="accept",
    )
    reached = []

    async def endpoint(request: Request):
        reached.append(request.scope["path"])
        return JSONResponse({"accepted": True})

    app = FastAPI()
    app.add_api_route("/navigation/walking", endpoint, methods=["POST"])
    app.add_api_route("/reports/v2", endpoint, methods=["POST"])
    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)

    def send(prepared, path, *, headers=None):
        # Send exactly what the Gateway signed: same method, target and body bytes.
        assert prepared["method"] == "POST"
        assert urlsplit(prepared["url"]).path == path and not urlsplit(prepared["url"]).query
        body = base64.b64decode(prepared["body_base64"])
        assert body == REQUEST_BODY

        async def exercise():
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://testserver",
            ) as client:
                return await client.post(path, content=body, headers=headers or prepared["headers"])
        return asyncio.run(exercise())

    targets = ["/navigation/walking", "/reports/v2"]
    original = session_headers(gateway_build, tmp_path, actor, targets=targets, device=device)
    assert original["epoch"] == 1
    for prepared in original["requests"].values():
        assert prepared["headers"]["x-walksafe-actor-assertion"].startswith("v4.")
    assert send(original["requests"]["/navigation/walking"], "/navigation/walking").status_code == 200
    replayed = send(original["requests"]["/navigation/walking"], "/navigation/walking")
    assert replayed.status_code == 401
    assert replayed.json()["detail"]["code"] == "actor_assertion_replayed"
    with isolated_engine.begin() as connection:
        connection.execute(update(UserAccount).where(UserAccount.id == account_id)
                           .values(auth_epoch=2, updated_at=datetime.now(timezone.utc)))
    reused = session_headers(
        gateway_build, tmp_path, actor, targets=targets, device=device, cookie=original["cookie"],
    )
    assert reused["epoch"] == 1, "the Gateway itself must not be updated by this test"
    for path in targets:
        denied = send(reused["requests"][path], path)
        assert denied.status_code == 401
        assert denied.json()["detail"]["code"] == "account_authentication_stale"
    legacy = dict(reused["requests"]["/navigation/walking"]["headers"])
    legacy["x-walksafe-actor-assertion"] = create_actor_assertion(
        actor, FieldTestAccess.FIELD, SECRET, account_generation=1,
    )
    assert send(
        reused["requests"]["/navigation/walking"], "/navigation/walking", headers=legacy,
    ).status_code == 401
    fresh = session_headers(gateway_build, tmp_path, actor, targets=["/reports/v2"], epoch=2, device=device)
    assert send(fresh["requests"]["/reports/v2"], "/reports/v2").status_code == 200
    assert reached == ["/navigation/walking", "/reports/v2"]
