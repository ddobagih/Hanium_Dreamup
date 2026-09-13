"""Exercise OTP delivery against a private TLS SMTP server, without external mail."""

from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from email import policy
from email.parser import BytesParser
from email.utils import parsedate_to_datetime
from ipaddress import ip_address
import socketserver
import ssl
from threading import Thread

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
def _smtp_server(context, security, *, reject_message):
    received = {}

    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            connection = self.request
            connection.settimeout(3)
            encrypted = security == "implicit_tls"
            if encrypted:
                connection = context.wrap_socket(connection, server_side=True)
            stream = connection.makefile("rwb", buffering=0)
            stream.write(b"220 localhost SMTP test\r\n")
            try:
                while command := stream.readline():
                    verb = command.split(b" ", 1)[0].strip().upper()
                    if verb == b"EHLO":
                        stream.write(
                            b"250-localhost\r\n250 AUTH PLAIN\r\n"
                            if encrypted
                            else b"250-localhost\r\n250 STARTTLS\r\n"
                        )
                    elif verb == b"STARTTLS":
                        stream.write(b"220 Ready for TLS\r\n")
                        stream.close()
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
