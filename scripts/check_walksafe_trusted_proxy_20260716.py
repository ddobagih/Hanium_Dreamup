#!/usr/bin/env python3
"""Run the packaged nginx trusted-client boundary against a hostile request."""

from __future__ import annotations

import argparse
import hashlib
import http.client
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from typing import Any


class ProxyCheckError(RuntimeError):
    pass


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return int(probe.getsockname()[1])


def _replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise ProxyCheckError(f"proxy example must contain exactly one {old!r}")
    return source.replace(old, new)


def _replace_exact_count(source: str, old: str, new: str, *, count: int) -> str:
    if source.count(old) != count:
        raise ProxyCheckError(f"proxy example must contain exactly {count} {old!r}")
    return source.replace(old, new)


def _render_config(
    source: str,
    *,
    edge_port: int,
    upstream_port: int,
    cert: Path,
    key: Path,
) -> str:
    rendered = _replace_once(source, "listen 443 ssl;", f"listen 127.0.0.1:{edge_port} ssl;")
    rendered = _replace_once(
        rendered,
        "server_name CHANGE_ME_WALKSAFE_HOSTNAME;",
        "server_name localhost;",
    )
    rendered = _replace_once(
        rendered,
        "ssl_certificate CHANGE_ME_TLS_CERTIFICATE_PATH;",
        f"ssl_certificate {cert};",
    )
    rendered = _replace_once(
        rendered,
        "ssl_certificate_key CHANGE_ME_TLS_PRIVATE_KEY_PATH;",
        f"ssl_certificate_key {key};",
    )
    return _replace_exact_count(
        rendered,
        "proxy_pass http://127.0.0.1:3000;",
        f"proxy_pass http://127.0.0.1:{upstream_port};",
        count=6,
    )


class _HeaderCapture(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_json(self, payload: dict[str, str]) -> None:
        body = json.dumps(payload, sort_keys=True).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API
        self._send_json({name.lower(): value for name, value in self.headers.items()})

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
        self._send_json({"method": self.command, "path": self.path})

    def log_message(self, _format: str, *_args: Any) -> None:
        return


def _wait_for_tls(port: int, process: subprocess.Popen[bytes]) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ProxyCheckError("nginx exited before accepting the TLS test request")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                return
        except OSError:
            time.sleep(0.05)
    raise ProxyCheckError("nginx did not accept the TLS test request")


def _require_trusted_tls_context(tls_context: ssl.SSLContext) -> None:
    if (
        tls_context.verify_mode != ssl.CERT_REQUIRED
        or tls_context.check_hostname is not True
    ):
        raise ProxyCheckError(
            "trusted proxy success request requires CA verification and hostname checking"
        )


def _trusted_tls_context(certificate: Path) -> ssl.SSLContext:
    context = ssl.create_default_context(
        purpose=ssl.Purpose.SERVER_AUTH,
        cafile=str(certificate),
    )
    _require_trusted_tls_context(context)
    return context


def _negative_test_tls_context() -> ssl.SSLContext:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def _trusted_https_connection(
    *,
    edge_port: int,
    tls_context: ssl.SSLContext,
) -> http.client.HTTPSConnection:
    _require_trusted_tls_context(tls_context)
    return http.client.HTTPSConnection(
        "127.0.0.1",
        edge_port,
        timeout=5,
        context=tls_context,
    )


def _assert_forwarded_headers(headers: dict[str, str]) -> None:
    canonical = {
        "host": "localhost",
        "cf-connecting-ip": "127.0.0.1",
        "x-real-ip": "127.0.0.1",
        "x-forwarded-for": "127.0.0.1",
        "x-forwarded-proto": "https",
    }
    for name, expected in canonical.items():
        if headers.get(name) != expected:
            raise ProxyCheckError(f"trusted proxy did not canonicalize {name}")
    for name in (
        "forwarded",
        "true-client-ip",
        "x-client-ip",
        "x-cluster-client-ip",
        "x-forwarded-host",
        "x-forwarded-port",
        "x-forwarded-server",
        "x-original-forwarded-for",
    ):
        if name in headers:
            raise ProxyCheckError(f"trusted proxy relayed spoofed header: {name}")


def _request_with_declared_body(
    *,
    edge_port: int,
    tls_context: ssl.SSLContext,
    path: str,
    content_length: int,
    body_prefix: bytes = b"",
) -> tuple[int, bytes]:
    connection = _trusted_https_connection(
        edge_port=edge_port,
        tls_context=tls_context,
    )
    connection.putrequest("POST", path, skip_host=True)
    connection.putheader("Host", "localhost")
    connection.putheader("Content-Type", "application/octet-stream")
    connection.putheader("Content-Length", str(content_length))
    connection.putheader("Connection", "close")
    connection.endheaders()
    if body_prefix:
        connection.send(body_prefix)
    response = connection.getresponse()
    body = response.read()
    connection.close()
    return response.status, body


def _assert_rejected_body_size(
    *,
    edge_port: int,
    tls_context: ssl.SSLContext,
    path: str,
    content_length: int,
) -> None:
    status, _body = _request_with_declared_body(
        edge_port=edge_port,
        tls_context=tls_context,
        path=path,
        content_length=content_length,
    )
    if status != 413:
        raise ProxyCheckError(
            f"trusted proxy accepted {content_length} declared bytes on {path}: HTTP {status}"
        )


def _assert_streamed_body_admission(
    *,
    edge_port: int,
    tls_context: ssl.SSLContext,
    path: str,
    content_length: int,
) -> None:
    status, body = _request_with_declared_body(
        edge_port=edge_port,
        tls_context=tls_context,
        path=path,
        content_length=content_length,
        body_prefix=b"x",
    )
    if status != 200:
        raise ProxyCheckError(
            f"trusted proxy did not stream an admitted request on {path}: HTTP {status}"
        )
    received = json.loads(body)
    if received != {"method": "POST", "path": path}:
        raise ProxyCheckError(f"upstream body-admission capture was malformed for {path}")


def check_proxy(*, nginx: Path, openssl: Path, config: Path) -> dict[str, str]:
    for executable, label in ((nginx, "nginx"), (openssl, "openssl")):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise ProxyCheckError(f"{label} executable is unavailable: {executable}")
    if config.is_symlink() or not config.is_file():
        raise ProxyCheckError("trusted proxy example must be a regular non-symlink file")
    source = config.read_text(encoding="utf-8")
    source_sha256 = hashlib.sha256(source.encode("utf-8")).hexdigest()

    edge_port = _free_port()
    upstream_port = _free_port()
    while upstream_port == edge_port:
        upstream_port = _free_port()
    with tempfile.TemporaryDirectory(prefix="walksafe-trusted-proxy-") as raw_temp:
        temp = Path(raw_temp)
        cert = temp / "certificate.pem"
        key = temp / "private-key.pem"
        subprocess.run(
            [
                str(openssl),
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                  "-days",
                  "1",
                  "-subj",
                  "/CN=127.0.0.1",
                  "-addext",
                  "subjectAltName=IP:127.0.0.1",
                  "-keyout",
                  str(key),
                  "-out",
                  str(cert),
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        rendered = temp / "walksafe-web.conf"
        rendered.write_text(
            _render_config(
                source,
                edge_port=edge_port,
                upstream_port=upstream_port,
                cert=cert,
                key=key,
            ),
            encoding="utf-8",
        )
        main = temp / "nginx.conf"
        for name in ("client-body", "proxy", "fastcgi", "uwsgi", "scgi"):
            (temp / name).mkdir()
        main.write_text(
            "\n".join(
                (
                    f"pid {temp / 'nginx.pid'};",
                    f"error_log {temp / 'error.log'} notice;",
                    "events {}",
                    "http {",
                    "    access_log off;",
                    f"    client_body_temp_path {temp / 'client-body'};",
                    f"    proxy_temp_path {temp / 'proxy'};",
                    f"    fastcgi_temp_path {temp / 'fastcgi'};",
                    f"    uwsgi_temp_path {temp / 'uwsgi'};",
                    f"    scgi_temp_path {temp / 'scgi'};",
                    f"    include {rendered};",
                    "}",
                    "",
                )
            ),
            encoding="utf-8",
        )

        upstream = http.server.ThreadingHTTPServer(("127.0.0.1", upstream_port), _HeaderCapture)
        upstream_thread = threading.Thread(target=upstream.serve_forever, daemon=True)
        upstream_thread.start()
        nginx_process: subprocess.Popen[bytes] | None = None
        try:
            syntax = subprocess.run(
                [str(nginx), "-p", f"{temp}/", "-c", str(main), "-t"],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            if syntax.returncode != 0:
                detail = syntax.stderr.decode("utf-8", errors="replace").strip()
                raise ProxyCheckError(
                    f"nginx rejected the rendered trusted proxy configuration: {detail}"
                )
            nginx_process = subprocess.Popen(
                [str(nginx), "-p", f"{temp}/", "-c", str(main), "-g", "daemon off;"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
              )
            _wait_for_tls(edge_port, nginx_process)
            tls_context = _trusted_tls_context(cert)
            connection = _trusted_https_connection(
                edge_port=edge_port,
                tls_context=tls_context,
            )
            connection.request(
                "GET",
                "/boundary-check",
                headers={
                    "Host": "attacker.example",
                    "CF-Connecting-IP": "198.51.100.10",
                    "X-Real-IP": "198.51.100.11",
                    "X-Forwarded-For": "198.51.100.12, 198.51.100.13",
                    "X-Forwarded-Proto": "http",
                    "Forwarded": "for=198.51.100.14;proto=http",
                    "True-Client-IP": "198.51.100.15",
                    "X-Client-IP": "198.51.100.16",
                    "X-Cluster-Client-IP": "198.51.100.17",
                    "X-Forwarded-Host": "attacker.example",
                    "X-Forwarded-Port": "80",
                    "X-Forwarded-Server": "attacker.example",
                    "X-Original-Forwarded-For": "198.51.100.18",
                },
            )
            response = connection.getresponse()
            body = response.read()
            connection.close()
            if response.status != 200:
                raise ProxyCheckError(f"trusted proxy returned HTTP {response.status}")
            received = json.loads(body)
            if not isinstance(received, dict) or not all(
                isinstance(name, str) and isinstance(value, str)
                for name, value in received.items()
            ):
                raise ProxyCheckError("upstream header capture was malformed")
            _assert_forwarded_headers(received)

            kibibyte = 1024
            image_limit = 9 * 1024 * 1024
            voice_limit = 11 * 1024 * 1024
            _assert_rejected_body_size(
                edge_port=edge_port,
                tls_context=tls_context,
                path="/ordinary-body-boundary",
                content_length=64 * kibibyte + 1,
            )
            for path in (
                "/api/detect",
                "/api/detect/v2",
                "/api/reports",
                "/api/reports/v2",
            ):
                _assert_rejected_body_size(
                    edge_port=edge_port,
                    tls_context=tls_context,
                    path=path,
                    content_length=image_limit + 1,
                )
                _assert_streamed_body_admission(
                    edge_port=edge_port,
                    tls_context=tls_context,
                    path=path,
                    content_length=image_limit,
                )
            _assert_rejected_body_size(
                edge_port=edge_port,
                tls_context=tls_context,
                path="/api/speech/stt",
                content_length=voice_limit + 1,
            )
            _assert_streamed_body_admission(
                edge_port=edge_port,
                tls_context=tls_context,
                path="/api/speech/stt",
                content_length=voice_limit,
            )
        finally:
            if nginx_process is not None and nginx_process.poll() is None:
                nginx_process.terminate()
                try:
                    nginx_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    nginx_process.kill()
                    nginx_process.wait(timeout=5)
            upstream.shutdown()
            upstream.server_close()
            upstream_thread.join(timeout=5)

    version = subprocess.run(
        [str(nginx), "-v"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return {
        "config_sha256": source_sha256,
        "nginx": (version.stderr or version.stdout).strip(),
        "result": "passed",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--nginx", type=Path, required=True)
    parser.add_argument("--openssl", type=Path, default=Path(shutil.which("openssl") or ""))
    parser.add_argument(
        "--config",
        type=Path,
        default=Path("deploy/nginx/walksafe-web.conf.example"),
    )
    args = parser.parse_args()
    print(
        json.dumps(
            check_proxy(
                nginx=args.nginx.resolve(),
                openssl=args.openssl.resolve(),
                config=args.config.absolute(),
            ),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
