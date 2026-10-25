"""Exercise OTP delivery against a private TLS SMTP server, without external mail."""

from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from email import policy
from email.parser import BytesParser
from email.utils import parsedate_to_datetime
from ipaddress import ip_address
import socket
import socketserver
import ssl
from threading import Event, Thread, enumerate as live_threads
from concurrent.futures import ThreadPoolExecutor
from time import monotonic, sleep

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
import pytest

from backend.app.services.accounts import OtpDeliveryError, SmtpOtpSender


@pytest.fixture
def smtp_tls_context(tmp_path, monkeypatch):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.now(UTC)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=1))
        .not_valid_after(now + timedelta(hours=1))
        .add_extension(
            x509.SubjectAlternativeName([x509.IPAddress(ip_address("127.0.0.1"))]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    cert_path = tmp_path / "smtp-test.pem"
    key_path = tmp_path / "smtp-test.key"
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    monkeypatch.setenv("SSL_CERT_FILE", str(cert_path))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert_path, key_path)
    return context


@contextmanager
def _smtp_server(context, security, *, reject_message=False, command_delay=0, slow_greeting=False, handshake_delay=0):
    received = {}

    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            connection = self.request
            connection.settimeout(3)
            encrypted = security == "implicit_tls"
            if encrypted:
                if handshake_delay:
                    sleep(handshake_delay)
                try:
                    connection = context.wrap_socket(connection, server_side=True)
                except (OSError, ssl.SSLError):
                    return
            stream = connection.makefile("rwb", buffering=0)
            try:
                if slow_greeting:
                    for chunk in (b"220", b" localhost", b" SMTP", b" test\r\n"):
                        sleep(0.1)
                        stream.write(chunk)
                else:
                    stream.write(b"220 localhost SMTP test\r\n")
            except (OSError, ssl.SSLError):
                stream.close()
                connection.close()
                return
            try:
                while command := stream.readline():
                    verb = command.split(b" ", 1)[0].strip().upper()
                    received.setdefault("commands", []).append(verb)
                    if command_delay:
                        sleep(command_delay)
                    if verb == b"EHLO":
                        stream.write(
                            b"250-localhost\r\n250 AUTH PLAIN\r\n"
                            if encrypted
                            else b"250-localhost\r\n250 STARTTLS\r\n"
                        )
                    elif verb == b"STARTTLS":
                        stream.write(b"220 Ready for TLS\r\n")
                        stream.close()
                        if handshake_delay:
                            sleep(handshake_delay)
                        connection = context.wrap_socket(connection, server_side=True)
                        stream = connection.makefile("rwb", buffering=0)
                        encrypted = True
                    elif verb == b"AUTH":
                        received["authenticated_over_tls"] = encrypted
                        stream.write(b"235 Authenticated\r\n")
                    elif verb == b"MAIL":
                        received["envelope_from"] = command
                        stream.write(b"250 Sender accepted\r\n")
                    elif verb == b"RCPT":
                        received["envelope_to"] = command
                        stream.write(b"250 Recipient accepted\r\n")
                    elif verb == b"DATA":
                        stream.write(b"354 Send message\r\n")
                        lines = []
                        while (line := stream.readline()) not in {b".\r\n", b""}:
                            lines.append(line)
                        received["message"] = b"".join(lines)
                        stream.write(
                            b"550 Message rejected\r\n"
                            if reject_message
                            else b"250 Message accepted\r\n"
                        )
                    elif verb == b"QUIT":
                        stream.write(b"221 Bye\r\n")
                        break
                    elif verb == b"RSET":
                        stream.write(b"250 Reset\r\n")
                    else:
                        stream.write(b"500 Unknown command\r\n")
            except (OSError, ssl.SSLError):
                pass  # A deadline intentionally disconnects before the reply.
            finally:
                stream.close()
                connection.close()

    with socketserver.TCPServer(("127.0.0.1", 0), Handler) as server:
        worker = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
        worker.start()
        try:
            yield server.server_address[1], received
        finally:
            server.shutdown()
            worker.join(timeout=5)


@pytest.mark.parametrize("security", ["implicit_tls", "starttls"])
@pytest.mark.parametrize("reject_message", [False, True])
def test_otp_delivery_over_authenticated_tls_smtp(
    smtp_tls_context, security, reject_message
):
    with _smtp_server(
        smtp_tls_context, security, reject_message=reject_message
    ) as (port, received):
        sender = SmtpOtpSender(
            host="127.0.0.1",
            port=port,
            security=security,
            username="smtp-test-user",
            password="smtp-test-password",
            from_address="no-reply@example.com",
            timeout_seconds=3,
        )
        if reject_message:
            with pytest.raises(OtpDeliveryError, match="^secure SMTP delivery failed$"):
                sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
        else:
            sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)

    assert received["authenticated_over_tls"] is True
    assert b"<no-reply@example.com>" in received["envelope_from"]
    assert b"<recipient@example.com>" in received["envelope_to"]
    message = BytesParser(policy=policy.default).parsebytes(received["message"])
    assert message["From"] == "no-reply@example.com"
    assert message["To"] == "recipient@example.com"
    assert parsedate_to_datetime(message["Date"]).tzinfo is UTC
    assert message["Message-ID"].startswith("<")
    assert message["Message-ID"].endswith("@example.com>")
    assert "012345" in message.get_content()
    assert "10 minutes" in message.get_content()
    assert not message.defects


@pytest.mark.parametrize("security", ["implicit_tls", "starttls"])
@pytest.mark.parametrize("phase", ["steps", "slow_greeting", "tls"])
def test_smtp_real_tls_deadline_bounds_stalled_and_slow_streams(smtp_tls_context, security, phase):
    options = {"command_delay": 0.12} if phase == "steps" else (
        {"slow_greeting": True} if phase == "slow_greeting" else {"handshake_delay": 0.5}
    )
    budget = 0.3
    with _smtp_server(smtp_tls_context, security, **options) as (port, received):
        sender = SmtpOtpSender(
            host="127.0.0.1", port=port, security=security,
            username="test", password="test", from_address="no-reply@example.com",
            timeout_seconds=budget,
        )
        started = monotonic()
        with pytest.raises(OtpDeliveryError):
            sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
        elapsed = monotonic() - started
        print(f"SMTP_BOUNDARY security={security} phase={phase} budget={budget} elapsed={elapsed:.3f}")
        assert elapsed < 0.6
        assert "message" not in received


@pytest.mark.parametrize("security", ["implicit_tls", "starttls"])
def test_data_accepted_closes_without_waiting_for_quit(smtp_tls_context, security):
    with _smtp_server(smtp_tls_context, security) as (port, received):
        sender = SmtpOtpSender(
            host="127.0.0.1", port=port, security=security,
            username="test", password="test", from_address="no-reply@example.com",
            timeout_seconds=1,
        )
        sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
    assert b"QUIT" not in received["commands"]
    assert received["message"]


def test_delayed_dns_does_not_outlive_the_delivery_deadline(smtp_tls_context, monkeypatch):
    original = socket.getaddrinfo
    def slow_dns(*args, **kwargs):
        sleep(0.5)
        return original(*args, **kwargs)
    with _smtp_server(smtp_tls_context, "implicit_tls") as (port, received):
        monkeypatch.setattr(socket, "getaddrinfo", slow_dns)
        sender = SmtpOtpSender(
            host="127.0.0.1", port=port, security="implicit_tls",
            username="test", password="test", from_address="no-reply@example.com",
            timeout_seconds=0.2,
        )
        started = monotonic()
        with pytest.raises(OtpDeliveryError):
            sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
        elapsed = monotonic() - started
        print(f"SMTP_BOUNDARY phase=dns budget=0.2 elapsed={elapsed:.3f}")
        assert elapsed < 0.4
        sleep(0.6)  # Late resolver completion must not open a connection or deliver mail.
        assert not received



def test_blocked_resolvers_are_bounded_and_late_completion_cannot_send(smtp_tls_context, monkeypatch):
    original = socket.getaddrinfo
    release = Event()
    started = []
    def blocked_dns(*args, **kwargs):
        started.append(True)
        release.wait(timeout=3)
        return original(*args, **kwargs)
    with _smtp_server(smtp_tls_context, "implicit_tls") as (port, received):
        monkeypatch.setattr(socket, "getaddrinfo", blocked_dns)
        sender = SmtpOtpSender(
            host="127.0.0.1", port=port, security="implicit_tls", username="", password="",
            from_address="no-reply@example.com", timeout_seconds=0.15,
        )
        def send():
            with pytest.raises(OtpDeliveryError):
                sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
        try:
            with ThreadPoolExecutor(max_workers=4) as pool:
                attempts = [pool.submit(send) for _ in range(4)]
                for attempt in attempts:
                    attempt.result(timeout=2)
            assert len(started) == 4
            assert len([thread for thread in live_threads() if thread.name == "walksafe-smtp-resolver"]) == 4
            for _ in range(6):
                send()
            assert len(started) == 4  # Capacity exhaustion cannot spawn more OS resolvers.
        finally:
            release.set()
        until = monotonic() + 1
        while any(thread.name == "walksafe-smtp-resolver" for thread in live_threads()) and monotonic() < until:
            sleep(0.01)
        assert not any(thread.name == "walksafe-smtp-resolver" for thread in live_threads())
        assert not received  # Late DNS answers have no network side effects.
        print("SMTP_BOUNDARY phase=resolver_capacity workers_max=4 late_connect=0 late_send=0 released_workers=0")


@pytest.mark.parametrize("security", ["implicit_tls", "starttls"])
def test_dns_and_tls_use_the_same_original_deadline(smtp_tls_context, monkeypatch, security):
    original = socket.getaddrinfo
    def delayed_dns(*args, **kwargs):
        sleep(0.15)
        return original(*args, **kwargs)
    with _smtp_server(smtp_tls_context, security, handshake_delay=0.3) as (port, received):
        monkeypatch.setattr(socket, "getaddrinfo", delayed_dns)
        sender = SmtpOtpSender(
            host="127.0.0.1", port=port, security=security, username="", password="",
            from_address="no-reply@example.com", timeout_seconds=0.3,
        )
        started = monotonic()
        with pytest.raises(OtpDeliveryError):
            sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
        elapsed = monotonic() - started
        assert elapsed < 0.4
        assert "message" not in received
        print(f"SMTP_BOUNDARY security={security} phase=dns_plus_tls budget=0.3 elapsed={elapsed:.3f}")



@pytest.mark.parametrize("security", ["implicit_tls", "starttls"])
def test_real_tls_hostname_mismatch_never_authenticates_or_sends(smtp_tls_context, monkeypatch, security):
    original = socket.getaddrinfo
    def local_address(host, port, *args):
        assert host == "mismatched-smtp.invalid"
        return original("127.0.0.1", port, *args)
    with _smtp_server(smtp_tls_context, security) as (port, received):
        monkeypatch.setattr(socket, "getaddrinfo", local_address)
        sender = SmtpOtpSender(
            host="mismatched-smtp.invalid", port=port, security=security,
            username="test", password="test", from_address="no-reply@example.com", timeout_seconds=1,
        )
        with pytest.raises(OtpDeliveryError):
            sender.send(email="recipient@example.com", code="012345", expires_in_seconds=600)
    assert "authenticated_over_tls" not in received
    assert "message" not in received
