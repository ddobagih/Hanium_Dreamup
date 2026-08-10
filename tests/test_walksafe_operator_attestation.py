from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

import pytest


ROOT = Path(__file__).resolve().parents[1]
FINGERPRINT = "a" * 40


def load_script(name: str):
    path = ROOT / "scripts" / name
    spec = importlib.util.spec_from_file_location(path.stem, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


operator_gate = load_script("verify_walksafe_operator_attestation_20260713.py")
signing_gate = load_script("verify_walksafe_signed_android_release_20260713.py")
verify_detached_signature_real = operator_gate._verify_detached_signature


def write(path: Path, content: str | bytes) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content, encoding="utf-8")
    return path


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(path: Path, display_path: str | None = None) -> dict[str, object]:
    return {
        "path": path.name if display_path is None else display_path,
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def json_write(path: Path, payload: dict[str, object]) -> Path:
    return write(path, json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def real_gpg() -> Path:
    candidate = shutil.which("gpg")
    if candidate is None:
        pytest.skip("system gpg is unavailable")
    return Path(candidate).resolve()


def real_apksigner() -> Path:
    candidates: list[Path] = []
    for environment_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(environment_name)
        if sdk:
            candidates.extend(sorted((Path(sdk) / "build-tools").glob("*/apksigner"), reverse=True))
    candidates.extend(
        sorted(Path("/home/ddobagi/Android/Sdk/build-tools").glob("*/apksigner"), reverse=True)
    )
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    pytest.skip("real Android SDK apksigner is unavailable")


def real_java_and_apksigner_jar() -> tuple[Path, Path]:
    java_name = shutil.which("java")
    if java_name is None:
        pytest.skip("system Java is unavailable")
    apksigner = real_apksigner()
    jar = (apksigner.parent / "lib" / "apksigner.jar").resolve()
    if not jar.is_file():
        pytest.skip("Android SDK apksigner.jar is unavailable")
    return Path(java_name).resolve(), jar


def git(root: Path, *arguments: str) -> str:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def tool_records(java: Path, apksigner_jar: Path) -> dict[str, object]:
    return {
        "java": {
            **record(java, str(java)),
            "runtime_trust": operator_gate.root_owned_system_trust(
                java,
                context="Java runtime",
                tree_root=java.parent.parent,
            ),
        },
        "apksigner_jar": record(apksigner_jar, str(apksigner_jar)),
    }


@pytest.fixture(autouse=True)
def trusted_detached_signature_boundary(monkeypatch: pytest.MonkeyPatch):
    def verify_signature(**arguments):
        assert arguments["signature"].size > 0
        assert arguments["attestation"].size > 0
        gpg = arguments["gpg_path"].expanduser().absolute()
        assert sha256(gpg) == arguments["expected_gpg_sha256"]
        assert arguments["expected_fingerprint"] == FINGERPRINT
        return (
            FINGERPRINT,
            FINGERPRINT,
            {
                **record(gpg, str(gpg)),
                "system_trust": operator_gate.root_owned_system_trust(
                    gpg,
                    context="gpg executable",
                ),
            },
            record(arguments["gpg_keyring_path"], str(arguments["gpg_keyring_path"])),
        )

    monkeypatch.setattr(operator_gate, "_verify_detached_signature", verify_signature)


def create_apk(path: Path, payload: bytes = b"source-bound-dex") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("classes.dex", payload)
        archive.writestr("AndroidManifest.xml", b"manifest")
    return path


def create_chain(tmp_path: Path, *, deployment_complete: bool = False) -> dict[str, Path]:
    source = tmp_path / "source"
    validator = write(
        source / operator_gate.VALIDATOR_PATH,
        "#!/usr/bin/env python3\n# fixed validator fixture\n",
    )
    policy = write(
        source / operator_gate.QUALITY_POLICY_PATH,
        '{"schema_version":"walksafe.product-quality-policy.v2"}\n',
    )
    subprocess.run(["git", "init", "-q", str(source)], check=True)
    git(source, "config", "user.name", "Operator Test")
    git(source, "config", "user.email", "operator@example.invalid")
    git(source, "add", ".")
    git(source, "commit", "-qm", "fixture")
    source_identity = {
        "commit": git(source, "rev-parse", "HEAD"),
        "tree": git(source, "rev-parse", "HEAD^{tree}"),
    }
    rc = tmp_path / "rc"
    unsigned = create_apk(rc / "android/app-release-unsigned.apk")
    unsigned_record = record(unsigned, "android/app-release-unsigned.apk")
    bundle = tmp_path / "validation-bundle"
    manifest_products: dict[str, dict[str, object]] = {}
    validation_products: dict[str, dict[str, object]] = {}
    closure_records: list[dict[str, object]] = [unsigned_record]
    for product in sorted(operator_gate.QUALITY_PRODUCTS):
        rc_receipt = json_write(
            rc / "quality" / product / f"walksafe-{product}-quality-receipt.json",
            {"schema_version": "walksafe.product-quality-receipt.v3", "result": "passed"},
        )
        receipt_record = record(
            rc_receipt,
            f"quality/{product}/walksafe-{product}-quality-receipt.json",
        )
        bundle_receipt = bundle / receipt_record["path"]
        write(bundle_receipt, rc_receipt.read_bytes())
        assert record(bundle_receipt, receipt_record["path"]) == receipt_record
        validation_products[product] = receipt_record
        manifest_products[product] = {
            "receipt": receipt_record,
            "evidence_files": [receipt_record],
        }
        closure_records.append(receipt_record)
    quality = {"policy_sha256": sha256(policy), "products": validation_products}
    manifest_quality = {
        "policy": record(policy, operator_gate.QUALITY_POLICY_PATH),
        "products": manifest_products,
    }
    closure_records.sort(key=lambda item: str(item["path"]))
    closure_canonical = "".join(
        f"{item['sha256']} {item['bytes']} {item['path']}\n" for item in closure_records
    ).encode("utf-8")
    blocker_ids = ["android-operator-signing", "operator-release-attestation"]
    manifest = {
        "schema_version": "walksafe.full-rc-manifest.v2",
        "source": {**source_identity, "worktree_clean": True},
        "release_state": {
            "kind": "release_candidate",
            "deployment_complete": deployment_complete,
        },
        "authentication": {
            "state": "external-attestation-required",
            "included": False,
            "deployment_allowed": False,
        },
        "closure": {
            "algorithm": "walksafe-path-size-sha256-lines.v1",
            "sha256": hashlib.sha256(closure_canonical).hexdigest(),
        },
        "files": closure_records,
        "quality": manifest_quality,
        "external_runtime_inputs": [
            {"id": blocker_id, "included": False, "required": "external"}
            for blocker_id in reversed(blocker_ids)
        ],
        "components": {
            "android": {
                "signing_status": "unsigned",
                "deployable": False,
                "artifact": unsigned_record,
            }
        },
    }
    manifest_path = json_write(rc / operator_gate.MANIFEST_NAME, manifest)

    java, apksigner_jar = real_java_and_apksigner_jar()
    validation_receipt = {
        "schema_version": "walksafe.full-rc-validation-receipt.v1",
        "result": "passed",
        "validator": {"path": operator_gate.VALIDATOR_PATH, "sha256": sha256(validator)},
        "manifest": record(manifest_path, operator_gate.MANIFEST_NAME),
        "closure_sha256": manifest["closure"]["sha256"],
        "source": source_identity,
        "blocker_ids": blocker_ids,
        "deployment_complete": False,
        "quality": quality,
        "tools": tool_records(java, apksigner_jar),
    }
    receipt_path = json_write(bundle / "walksafe-full-rc-validation-receipt.json", validation_receipt)
    attestation = {
        "schema_version": "walksafe.operator-release-attestation.v1",
        "manifest": record(manifest_path, operator_gate.MANIFEST_NAME),
        "closure_sha256": manifest["closure"]["sha256"],
        "source": source_identity,
        "validation_receipt": record(receipt_path),
        "blocker_ids": blocker_ids,
        "deployment_complete": False,
        "quality": quality,
    }
    review = tmp_path / "operator-review"
    attestation_path = json_write(review / "walksafe-operator-attestation.json", attestation)
    signature = write(review / "walksafe-operator-attestation.json.asc", b"detached-signature-fixture\n")
    keyring = write(tmp_path / "approved-reviewers.gpg", b"public-keyring-fixture\n")
    gpg = real_gpg()
    return {
        "source": source,
        "manifest": manifest_path,
        "unsigned": unsigned,
        "receipt": receipt_path,
        "attestation": attestation_path,
        "signature": signature,
        "keyring": keyring,
        "gpg": gpg,
        "java": java,
        "apksigner_jar": apksigner_jar,
    }


def verify(chain: dict[str, Path], **overrides):
    arguments = {
        "source_root": chain["source"],
        "manifest_path": chain["manifest"],
        "validation_receipt_path": chain["receipt"],
        "attestation_path": chain["attestation"],
        "signature_path": chain["signature"],
        "expected_operator_fingerprint": FINGERPRINT,
        "gpg_path": chain["gpg"],
        "expected_gpg_sha256": sha256(chain["gpg"]),
        "java_path": chain["java"],
        "expected_java_sha256": sha256(chain["java"]),
        "apksigner_jar_path": chain["apksigner_jar"],
        "expected_apksigner_jar_sha256": sha256(chain["apksigner_jar"]),
        "gpg_keyring_path": chain["keyring"],
    }
    arguments.update(overrides)
    return operator_gate.verify_operator_attestation(**arguments)


def rewrite_quality_binding(chain: dict[str, Path], mutate) -> None:
    receipt = json.loads(chain["receipt"].read_text(encoding="utf-8"))
    mutate(receipt["quality"])
    json_write(chain["receipt"], receipt)
    attestation = json.loads(chain["attestation"].read_text(encoding="utf-8"))
    attestation["quality"] = receipt["quality"]
    attestation["validation_receipt"] = record(chain["receipt"])
    json_write(chain["attestation"], attestation)


def test_valid_operator_attestation_binds_manifest_receipt_quality_and_gpg_identity(tmp_path: Path) -> None:
    chain = create_chain(tmp_path)

    result = verify(chain)

    assert result["verified"] is True
    assert result["manifest"]["sha256"] == sha256(chain["manifest"])
    assert result["validation_receipt"]["sha256"] == sha256(chain["receipt"])
    assert result["attestation"]["sha256"] == sha256(chain["attestation"])
    assert result["operator_fingerprint"] == FINGERPRINT
    assert result["operator_primary_fingerprint"] == FINGERPRINT
    assert result["operator_signing_fingerprint"] == FINGERPRINT
    assert result["gpg"]["sha256"] == sha256(chain["gpg"])
    assert result["validation_tools"] == tool_records(chain["java"], chain["apksigner_jar"])


def test_operator_attestation_rejects_missing_quality_binding(tmp_path: Path) -> None:
    chain = create_chain(tmp_path)
    receipt = json.loads(chain["receipt"].read_text(encoding="utf-8"))
    receipt.pop("quality")
    json_write(chain["receipt"], receipt)
    attestation = json.loads(chain["attestation"].read_text(encoding="utf-8"))
    attestation.pop("quality")
    attestation["validation_receipt"] = record(chain["receipt"])
    json_write(chain["attestation"], attestation)

    with pytest.raises(operator_gate.AttestationVerificationError, match="fields"):
        verify(chain)


def test_operator_attestation_rejects_missing_detached_signature(tmp_path: Path) -> None:
    chain = create_chain(tmp_path)
    chain["signature"].unlink()

    with pytest.raises(operator_gate.AttestationVerificationError, match="signature"):
        verify(chain)


def test_operator_attestation_rejects_untrusted_gpg_executable(tmp_path: Path) -> None:
    chain = create_chain(tmp_path)
    fake = tmp_path / "gpg"
    shutil.copyfile(chain["gpg"], fake)
    fake.chmod(0o755)

    with operator_gate.FileSnapshot.capture(
        chain["attestation"],
        context="operator attestation",
    ) as attestation, operator_gate.FileSnapshot.capture(
        chain["signature"],
        context="operator signature",
    ) as signature:
        with pytest.raises(operator_gate.AttestationVerificationError, match="root-owned"):
            verify_detached_signature_real(
                attestation=attestation,
                signature=signature,
                expected_fingerprint=FINGERPRINT,
                gpg_path=fake.resolve(),
                expected_gpg_sha256=sha256(fake),
                gpg_keyring_path=chain["keyring"],
            )


@pytest.mark.parametrize(
    ("status", "expected_signing"),
    [
        (
            f"[GNUPG:] VALIDSIG {FINGERPRINT} 2026-07-13 1 0 4 0 1 10 00",
            FINGERPRINT,
        ),
        (
            f"[GNUPG:] VALIDSIG {'b' * 40} 2026-07-13 1 0 4 0 1 10 00 {FINGERPRINT}",
            "b" * 40,
        ),
    ],
)
def test_validsig_distinguishes_primary_and_signing_fingerprints(
    status: str,
    expected_signing: str,
) -> None:
    signing, primary = operator_gate._validsig_fingerprints(status)

    assert primary == FINGERPRINT
    assert signing == expected_signing


def test_gpg_verification_accepts_approved_primary_via_signing_subkey(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    signing_fingerprint = "b" * 40
    status = (
        f"[GNUPG:] GOODSIG {signing_fingerprint[-16:]} Reviewer\n"
        f"[GNUPG:] VALIDSIG {signing_fingerprint} "
        f"2026-07-13 1 0 4 0 1 10 00 {FINGERPRINT}\n"
    )
    monkeypatch.setattr(
        operator_gate.subprocess,
        "run",
        lambda *args, **kwargs: subprocess.CompletedProcess(args[0], 0, status, ""),
    )
    attestation = write(tmp_path / "attestation.json", b"{}\n")
    signature = write(tmp_path / "attestation.json.asc", b"signature\n")
    keyring = write(tmp_path / "reviewers.gpg", b"keyring\n")
    gpg = real_gpg()

    with operator_gate.FileSnapshot.capture(
        attestation,
        context="operator attestation",
    ) as attestation_snapshot, operator_gate.FileSnapshot.capture(
        signature,
        context="operator signature",
    ) as signature_snapshot:
        primary, signing, _gpg_record, _keyring_record = verify_detached_signature_real(
            attestation=attestation_snapshot,
            signature=signature_snapshot,
            expected_fingerprint=FINGERPRINT,
            gpg_path=gpg,
            expected_gpg_sha256=sha256(gpg),
            gpg_keyring_path=keyring,
        )

    assert primary == FINGERPRINT
    assert signing == signing_fingerprint


@pytest.mark.parametrize(
    ("extra_status", "hash_algorithm", "signature_class", "message"),
    [
        (
            "[GNUPG:] KEYEXPIRED 1\n[GNUPG:] EXPKEYSIG bbbbbbbbbbbbbbbb Reviewer\n",
            10,
            "00",
            "expired, revoked, or invalid",
        ),
        ("", 2, "00", "digest must be SHA-256"),
        ("", 10, "01", "binary-document signature class 00"),
    ],
)
def test_gpg_verification_rejects_invalid_status_or_weak_digest_even_with_validsig(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    extra_status: str,
    hash_algorithm: int,
    signature_class: str,
    message: str,
) -> None:
    signing_fingerprint = "b" * 40
    status = (
        f"[GNUPG:] GOODSIG {signing_fingerprint[-16:]} Reviewer\n"
        f"{extra_status}"
        f"[GNUPG:] VALIDSIG {signing_fingerprint} "
        f"2026-07-13 1 0 4 0 1 {hash_algorithm} {signature_class} {FINGERPRINT}\n"
    )
    monkeypatch.setattr(
        operator_gate.subprocess,
        "run",
        lambda *args, **kwargs: subprocess.CompletedProcess(args[0], 0, status, ""),
    )
    attestation = write(tmp_path / "attestation.json", b"{}\n")
    signature = write(tmp_path / "attestation.json.asc", b"signature\n")
    keyring = write(tmp_path / "reviewers.gpg", b"keyring\n")
    gpg = real_gpg()

    with operator_gate.FileSnapshot.capture(
        attestation,
        context="operator attestation",
    ) as attestation_snapshot, operator_gate.FileSnapshot.capture(
        signature,
        context="operator signature",
    ) as signature_snapshot:
        with pytest.raises(
            operator_gate.AttestationVerificationError,
            match=message,
        ):
            verify_detached_signature_real(
                attestation=attestation_snapshot,
                signature=signature_snapshot,
                expected_fingerprint=FINGERPRINT,
                gpg_path=gpg,
                expected_gpg_sha256=sha256(gpg),
                gpg_keyring_path=keyring,
            )


def test_gpg_verification_rejects_attestation_replaced_during_subprocess(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    attestation = json_write(tmp_path / "attestation.json", {"state": "reviewed"})
    signature = write(tmp_path / "attestation.json.asc", b"detached-signature\n")
    keyring = write(tmp_path / "reviewers.gpg", b"public-keyring\n")
    gpg = real_gpg()
    original = attestation.read_bytes()

    def replace_attestation(*args, **kwargs):
        command = args[0]
        keyring_argument = command[command.index("--keyring") + 1]
        assert keyring_argument.startswith("/proc/self/fd/")
        assert int(keyring_argument.rsplit("/", 1)[1]) in kwargs["pass_fds"]
        assert str(keyring) not in command
        json_write(attestation, {"state": "replaced"})
        restored = attestation.with_name(".attestation-restored.json")
        restored.write_bytes(original)
        os.replace(restored, attestation)
        status = (
            f"[GNUPG:] GOODSIG {FINGERPRINT[-16:]} Reviewer\n"
            f"[GNUPG:] VALIDSIG {FINGERPRINT} 2026-07-13 1 0 4 0 1 10 00\n"
        )
        return subprocess.CompletedProcess(args[0], 0, status, "")

    monkeypatch.setattr(operator_gate.subprocess, "run", replace_attestation)
    with operator_gate.FileSnapshot.capture(
        attestation,
        context="operator attestation",
    ) as attestation_snapshot, operator_gate.FileSnapshot.capture(
        signature,
        context="operator signature",
    ) as signature_snapshot:
        with pytest.raises(operator_gate.AttestationVerificationError, match="changed during signature"):
            verify_detached_signature_real(
                attestation=attestation_snapshot,
                signature=signature_snapshot,
                expected_fingerprint=FINGERPRINT,
                gpg_path=gpg,
                expected_gpg_sha256=sha256(gpg),
                gpg_keyring_path=keyring,
            )


@pytest.mark.parametrize("stale_file", ["manifest", "receipt"])
def test_operator_attestation_rejects_stale_manifest_or_receipt(tmp_path: Path, stale_file: str) -> None:
    chain = create_chain(tmp_path)
    with chain[stale_file].open("ab") as destination:
        destination.write(b" \n")

    with pytest.raises(operator_gate.AttestationVerificationError, match="stale|record"):
        verify(chain)


def test_operator_attestation_rejects_deployment_complete_true(tmp_path: Path) -> None:
    chain = create_chain(tmp_path, deployment_complete=True)

    with pytest.raises(operator_gate.AttestationVerificationError, match="deployment_complete=false"):
        verify(chain)


def test_operator_attestation_rechecks_rc_closure_after_validation(tmp_path: Path) -> None:
    chain = create_chain(tmp_path)
    create_apk(chain["unsigned"], payload=b"tampered-after-validation")

    with pytest.raises(operator_gate.AttestationVerificationError, match="changed after validation"):
        verify(chain)


def test_operator_attestation_requires_manifest_receipt_and_bundle_quality_exact_match(
    tmp_path: Path,
) -> None:
    chain = create_chain(tmp_path)
    manifest = json.loads(chain["manifest"].read_text(encoding="utf-8"))
    manifest["quality"]["products"]["voice"]["receipt"]["sha256"] = "0" * 64
    json_write(chain["manifest"], manifest)
    receipt = json.loads(chain["receipt"].read_text(encoding="utf-8"))
    receipt["manifest"] = record(chain["manifest"], operator_gate.MANIFEST_NAME)
    json_write(chain["receipt"], receipt)
    attestation = json.loads(chain["attestation"].read_text(encoding="utf-8"))
    attestation["manifest"] = record(chain["manifest"], operator_gate.MANIFEST_NAME)
    attestation["validation_receipt"] = record(chain["receipt"])
    json_write(chain["attestation"], attestation)

    with pytest.raises(operator_gate.AttestationVerificationError, match="quality record differs"):
        verify(chain)


def test_operator_attestation_rejects_corrupted_validation_bundle_copy(tmp_path: Path) -> None:
    chain = create_chain(tmp_path)
    receipt = json.loads(chain["receipt"].read_text(encoding="utf-8"))
    relative = receipt["quality"]["products"]["web"]["path"]
    (chain["receipt"].parent / relative).write_bytes(b"corrupted bundle receipt\n")

    with pytest.raises(operator_gate.AttestationVerificationError, match="stale"):
        verify(chain)


def test_operator_attestation_rejects_duplicate_manifest_quality_evidence(
    tmp_path: Path,
) -> None:
    chain = create_chain(tmp_path)
    manifest = json.loads(chain["manifest"].read_text(encoding="utf-8"))
    evidence = manifest["quality"]["products"]["voice"]["evidence_files"]
    evidence.append(dict(evidence[0]))
    json_write(chain["manifest"], manifest)
    receipt = json.loads(chain["receipt"].read_text(encoding="utf-8"))
    receipt["manifest"] = record(chain["manifest"], operator_gate.MANIFEST_NAME)
    json_write(chain["receipt"], receipt)
    attestation = json.loads(chain["attestation"].read_text(encoding="utf-8"))
    attestation["manifest"] = record(chain["manifest"], operator_gate.MANIFEST_NAME)
    attestation["validation_receipt"] = record(chain["receipt"])
    json_write(chain["attestation"], attestation)

    with pytest.raises(operator_gate.AttestationVerificationError, match="evidence closure"):
        verify(chain)


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda quality: quality["products"].pop("voice"), "product set"),
        (
            lambda quality: quality["products"]["web"].__setitem__("path", "../outside.json"),
            "unsafe",
        ),
        (lambda quality: quality["products"]["web"].__setitem__("bytes", 0), "invalid"),
    ],
)
def test_operator_attestation_rejects_malformed_quality_binding(tmp_path: Path, mutate, message: str) -> None:
    chain = create_chain(tmp_path)
    rewrite_quality_binding(chain, mutate)

    with pytest.raises(operator_gate.AttestationVerificationError, match=message):
        verify(chain)


def test_signed_android_gate_preserves_payload_equality_with_trusted_tool_boundary(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    chain = create_chain(tmp_path)
    signed = tmp_path / "app-release-signed.apk"
    shutil.copyfile(chain["unsigned"], signed)
    java = chain["java"]
    apksigner_jar = chain["apksigner_jar"]
    manifest = json.loads(chain["manifest"].read_text(encoding="utf-8"))

    monkeypatch.setattr(
        signing_gate,
        "verify_operator_attestation",
        lambda **_: {
            "manifest": record(chain["manifest"], operator_gate.MANIFEST_NAME),
            "closure_sha256": manifest["closure"]["sha256"],
            "validation_receipt": record(chain["receipt"]),
            "attestation": record(chain["attestation"]),
            "detached_signature": record(chain["signature"]),
            "operator_fingerprint": FINGERPRINT,
            "operator_primary_fingerprint": FINGERPRINT,
            "operator_signing_fingerprint": FINGERPRINT,
            "operator_fingerprint_sha256": hashlib.sha256(FINGERPRINT.encode()).hexdigest(),
            "gpg": record(chain["gpg"], str(chain["gpg"])),
            "gpg_keyring": record(chain["keyring"], str(chain["keyring"])),
            "validation_tools": tool_records(java, apksigner_jar),
        },
    )
    certificate_output = (
        "Signer #1 certificate SHA-256 digest: " + ":".join(["aa"] * 32) + "\n"
    )
    def successful_apksigner(*args, **kwargs):
        return subprocess.CompletedProcess(args[0], 0, certificate_output, "")

    monkeypatch.setattr(signing_gate.subprocess, "run", successful_apksigner)
    arguments = {
        "source_root": chain["source"],
        "manifest_path": chain["manifest"],
        "unsigned_apk_path": chain["unsigned"],
        "signed_apk_path": signed,
        "expected_cert_sha256": "aa" * 32,
        "validation_receipt_path": chain["receipt"],
        "operator_attestation_path": chain["attestation"],
        "operator_attestation_signature_path": chain["signature"],
        "expected_operator_fingerprint": FINGERPRINT,
        "gpg_path": chain["gpg"],
        "expected_gpg_sha256": sha256(chain["gpg"]),
        "gpg_keyring_path": chain["keyring"],
        "java_path": java,
        "expected_java_sha256": sha256(java),
        "apksigner_jar_path": apksigner_jar,
        "expected_apksigner_jar_sha256": sha256(apksigner_jar),
    }

    result = signing_gate.verify_signed_release(**arguments)
    assert result["schema_version"] == "walksafe.android-signing-gate.v2"
    assert result["full_rc_manifest"]["sha256"] == sha256(chain["manifest"])
    assert result["operator_attestation"]["sha256"] == sha256(chain["attestation"])
    assert result["operator_fingerprint_sha256"] == hashlib.sha256(FINGERPRINT.encode()).hexdigest()
    signed_original = signed.read_bytes()

    def replace_signed_during_apksigner(*args, **kwargs):
        create_apk(signed, payload=b"replaced-during-apksigner")
        restored = signed.with_name(".signed-restored.apk")
        restored.write_bytes(signed_original)
        os.replace(restored, signed)
        return subprocess.CompletedProcess(args[0], 0, certificate_output, "")

    monkeypatch.setattr(signing_gate.subprocess, "run", replace_signed_during_apksigner)
    with pytest.raises(signing_gate.SigningGateError, match="changed during signed release"):
        signing_gate.verify_signed_release(**arguments)

    monkeypatch.setattr(signing_gate.subprocess, "run", successful_apksigner)
    create_apk(signed, payload=b"changed-after-operator-attestation")
    with pytest.raises(signing_gate.SigningGateError, match="payload differs"):
        signing_gate.verify_signed_release(**arguments)


def test_signed_gate_receipt_publication_preserves_concurrent_competitor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = tmp_path / "source"
    rc = tmp_path / "rc"
    bundle = tmp_path / "bundle"
    for directory in (source, rc, bundle):
        directory.mkdir()
    receipt = tmp_path / "signed-receipt.json"
    receipt.write_text("competitor\n", encoding="utf-8")
    competitor_inode = receipt.stat().st_ino
    monkeypatch.setattr(signing_gate, "require_isolated_python", lambda _: None)
    monkeypatch.setattr(signing_gate, "verify_signed_release", lambda **_: {"verified": True})
    monkeypatch.setattr(
        sys,
        "argv",
            [
                "verify-signed",
                "--source-root", str(source),
                "--manifest", str(rc / "manifest.json"),
            "--unsigned-apk", str(tmp_path / "unsigned.apk"),
            "--signed-apk", str(tmp_path / "signed.apk"),
            "--expected-cert-sha256", "a" * 64,
                "--validation-receipt", str(bundle / "validation.json"),
            "--operator-attestation", str(tmp_path / "attestation.json"),
            "--operator-attestation-signature", str(tmp_path / "attestation.asc"),
            "--expected-operator-fingerprint", "b" * 40,
            "--gpg", "/usr/bin/gpg",
            "--expected-gpg-sha256", "c" * 64,
            "--gpg-keyring", str(tmp_path / "keyring.gpg"),
            "--java", "/usr/bin/java",
            "--expected-java-sha256", "d" * 64,
            "--apksigner-jar", str(tmp_path / "apksigner.jar"),
            "--expected-apksigner-jar-sha256", "e" * 64,
            "--receipt", str(receipt),
        ],
    )

    with pytest.raises(SystemExit, match="publication failed"):
        signing_gate.main()

    assert receipt.stat().st_ino == competitor_inode
    assert receipt.read_text(encoding="utf-8") == "competitor\n"
