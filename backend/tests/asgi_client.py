from __future__ import annotations

import asyncio
from typing import Any

import httpx


class ASGITestClient:
    def __init__(self, app: Any) -> None:
        self._app = app

    def request(self, method: str, url: str, **kwargs: Any) -> httpx.Response:
        async def send() -> httpx.Response:
            transport = httpx.ASGITransport(app=self._app)
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
                response = await client.request(method, url, **kwargs)
                await response.aread()
                return response

        return asyncio.run(send())

    def get(self, url: str, **kwargs: Any) -> httpx.Response:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs: Any) -> httpx.Response:
        return self.request("POST", url, **kwargs)

    def patch(self, url: str, **kwargs: Any) -> httpx.Response:
        return self.request("PATCH", url, **kwargs)
