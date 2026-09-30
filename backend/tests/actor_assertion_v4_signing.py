"""Test-only Gateway stand-in that signs request-bound v4 actor assertions.

w24 S20-41 / owner decision 7: the Backend default is ``safe-methods``, so a write
must carry a v4 actor assertion bound to the exact method, origin-form target and
body bytes. Real Gateways sign what they forward; this wrapper does the same for
ASGI tests. A request whose actor-assertion header equals ``V4_SIGN_AT_SEND`` gets
a fresh v4 token (new jti per request) computed from the bytes the app receives;
every other request passes through unchanged, so negative tests keep their own
headers.
"""

from __future__ import annotations

import hashlib
from typing import Any

from backend.app.field_test_security import (
    FieldTestAccess,
    actor_assertion_request_target,
    create_actor_assertion_v4,
)

V4_SIGN_AT_SEND = "v4-sign-at-send"
ACTOR_ASSERTION_HEADER = "x-walksafe-actor-assertion"


def _header(scope: dict[str, Any], name: str) -> str | None:
    wanted = name.lower().encode("latin-1")
    for key, value in scope.get("headers") or []:
        if key.lower() == wanted:
            return value.decode("latin-1")
    return None


class GatewayV4Signer:
    def __init__(
        self,
        app: Any,
        secret: str,
        *,
        auth_epoch: int | None = None,
        access: FieldTestAccess = FieldTestAccess.FIELD,
    ) -> None:
        self.app = app
        self._secret = secret
        self._auth_epoch = auth_epoch
        self._access = access

    def __getattr__(self, name: str) -> Any:
        # Tests may still read FastAPI attributes (routes, state) of the wrapped app.
        return getattr(self.app, name)

    async def __call__(self, scope: dict[str, Any], receive: Any, send: Any) -> None:
        if (
            scope.get("type") != "http"
            or _header(scope, ACTOR_ASSERTION_HEADER) != V4_SIGN_AT_SEND
        ):
            await self.app(scope, receive, send)
            return
        messages: list[dict[str, Any]] = []
        body = bytearray()
        while True:
            message = await receive()
            messages.append(message)
            if message["type"] != "http.request":
                break
            body.extend(message.get("body", b""))
            if not message.get("more_body", False):
                break
        target = actor_assertion_request_target(scope)
        assert target is not None, "test request target is not origin-form ASCII"
        generation = _header(scope, "x-walksafe-account-generation")
        assert generation is not None, "v4 needs x-walksafe-account-generation"
        assertion = create_actor_assertion_v4(
            _header(scope, "x-walksafe-actor-id") or "",
            self._access,
            self._secret,
            account_generation=int(generation),
            auth_epoch=self._auth_epoch,
            method=str(scope["method"]).upper(),
            target=target,
            body_sha256=hashlib.sha256(bytes(body)).hexdigest(),
        )
        wanted = ACTOR_ASSERTION_HEADER.lower().encode("latin-1")
        headers = [
            (key, assertion.encode("ascii") if key.lower() == wanted else value)
            for key, value in scope["headers"]
        ]
        replay = list(messages)

        async def replay_receive() -> dict[str, Any]:
            if replay:
                return replay.pop(0)
            return await receive()

        await self.app({**scope, "headers": headers}, replay_receive, send)
