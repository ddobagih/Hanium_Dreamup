from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

import pytest

from scripts import check_android_apk_model_asset_20260713 as checker
from scripts.check_android_apk_model_asset_20260713 import verify_apk


def write_fixture(tmp_path: Path, *, extra_entry: str | None = None, model_payload: bytes = b"model") -> tuple[Path, Path]:
    model_hash = hashlib.sha256(b"model").hexdigest()
    config = {
        "primary_model": "unified_walksafe",
        "models": {
            "unified_walksafe": {
                "asset": "models/walksafe.tflite",
                "artifact_sha256": model_hash,
            }
        }
    }
    config_path = tmp_path / "runtime.json"
    config_bytes = (json.dumps(config, indent=2) + "\n").encode()
    config_path.write_bytes(config_bytes)
    apk_path = tmp_path / "app.apk"
    with zipfile.ZipFile(apk_path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("assets/model-config/two_model_runtime.json", config_bytes)
        archive.writestr("assets/models/walksafe.tflite", model_payload)
        if extra_entry:
            archive.writestr(extra_entry, b"payload")
    return apk_path, config_path


def test_accepts_exact_config_and_model_payload(tmp_path: Path) -> None:
    apk, config = write_fixture(tmp_path)
    assert verify_apk(apk, config) == (1, 3)


def test_rejects_model_hash_mismatch(tmp_path: Path) -> None:
    apk, config = write_fixture(tmp_path, model_payload=b"changed")
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        verify_apk(apk, config)


@pytest.mark.parametrize("mutation", ["missing_hash", "invalid_hash", "shared_asset"])
@pytest.mark.parametrize("profile", ["debug", "release"])
def test_primary_hash_cannot_be_removed_or_shadowed(tmp_path: Path, mutation: str, profile: str) -> None:
    apk, config_path = write_fixture(tmp_path)
    config = json.loads(config_path.read_text())
    primary = config["models"]["unified_walksafe"]
    if mutation == "missing_hash":
        del primary["artifact_sha256"]
        message = "requires artifact_sha256"
    elif mutation == "invalid_hash":
        primary["artifact_sha256"] = "not-a-sha256"
        message = "invalid artifact SHA-256"
    else:
        # A later declaration must not replace the primary's hash with None.
        config["models"]["legacy"] = {"asset": primary["asset"]}
        message = "declared twice"
    config_path.write_text(json.dumps(config))
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("assets/model-config/two_model_runtime.json", config_path.read_bytes())
        archive.writestr("assets/models/walksafe.tflite", b"modeL")
    with pytest.raises(ValueError, match=message):
        verify_apk(apk, config_path, profile=profile)


@pytest.mark.parametrize("entry", ["assets/models/unused.tflite", "assets/logs/session.jsonl", "assets/.env"])
def test_rejects_unconfigured_model_or_forbidden_payload(tmp_path: Path, entry: str) -> None:
    apk, config = write_fixture(tmp_path, extra_entry=entry)
    with pytest.raises(ValueError):
        verify_apk(apk, config)


def rewrite_apk(apk: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(apk, "w") as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload)


def apk_entries(apk: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(apk) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def add_inactive_model(apk: Path, config_path: Path) -> str:
    config = json.loads(config_path.read_text())
    config["models"]["inactive"] = {
        "asset": "models/inactive.tflite",
        "artifact_sha256": hashlib.sha256(b"inactive").hexdigest(),
    }
    config_path.write_text(json.dumps(config))
    entries = apk_entries(apk)
    entries["assets/model-config/two_model_runtime.json"] = config_path.read_bytes()
    rewrite_apk(apk, entries)
    return "assets/models/inactive.tflite"


def test_release_packages_only_loaded_models_and_rejects_inactive_payload(tmp_path: Path) -> None:
    apk, config = write_fixture(tmp_path)
    inactive = add_inactive_model(apk, config)
    assert verify_apk(apk, config, profile="release") == (1, 3)
    entries = apk_entries(apk)
    entries[inactive] = b"inactive"
    rewrite_apk(apk, entries)
    with pytest.raises(ValueError, match="unexpected=.*inactive"):
        verify_apk(apk, config, profile="release")


def test_release_requires_declared_non_null_fallback_and_its_hash(tmp_path: Path) -> None:
    apk, config_path = write_fixture(tmp_path)
    inactive = add_inactive_model(apk, config_path)
    config = json.loads(config_path.read_text())
    config["fallback_model"] = "inactive"
    config_path.write_text(json.dumps(config))
    entries = apk_entries(apk)
    entries["assets/model-config/two_model_runtime.json"] = config_path.read_bytes()
    rewrite_apk(apk, entries)
    with pytest.raises(ValueError, match="missing=.*inactive"):
        verify_apk(apk, config_path, profile="release")
    entries[inactive] = b"inactive"
    rewrite_apk(apk, entries)
    assert verify_apk(apk, config_path, profile="release") == (2, 4)
    entries[inactive] = b"changed"
    rewrite_apk(apk, entries)
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        verify_apk(apk, config_path, profile="release")


@pytest.mark.parametrize("fallback", ["undeclared", 1])
def test_rejects_invalid_fallback_declaration(tmp_path: Path, fallback: object) -> None:
    apk, config_path = write_fixture(tmp_path)
    config = json.loads(config_path.read_text())
    config["fallback_model"] = fallback
    config_path.write_text(json.dumps(config))
    with pytest.raises(ValueError, match="fallback_model must be null or a declared model"):
        verify_apk(apk, config_path, profile="release")


def test_debug_comparison_hashes_match_preserved_source_contracts() -> None:
    assert checker.DEBUG_COMPARISON_MODELS == {
        "assets/models/walksafe_unified_yolo26n_768_float32.tflite":
            "92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19",
        "assets/models/walksafe_unified_yolo26n_768_float32_gpu_compatible.tflite":
            "25ef119d4f07e1bffbd4ac6827fa46a44a0fa3deaaedfb0c867e7c655f813559",
    }
    contract = checker.ROOT / "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/inference/UnifiedTfliteAssetContractTest.kt"
    for entry, digest in checker.DEBUG_COMPARISON_MODELS.items():
        assert entry.removeprefix("assets/models/") in contract.read_text()
        assert digest in contract.read_text()


def write_debug_fixture(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[Path, Path]:
    apk, config = write_fixture(tmp_path)
    entries = apk_entries(apk)
    # Controlled tiny payloads test profile enforcement without replacing real model files.
    comparisons = {name: f"comparison-{index}".encode() for index, name in enumerate(checker.DEBUG_COMPARISON_MODELS)}
    monkeypatch.setattr(checker, "DEBUG_COMPARISON_MODELS", {
        name: hashlib.sha256(payload).hexdigest() for name, payload in comparisons.items()
    })
    rewrite_apk(apk, {**entries, **comparisons})
    return apk, config


def test_debug_requires_configured_assets_and_exact_comparison_hashes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    apk, config = write_debug_fixture(tmp_path, monkeypatch)
    inactive = add_inactive_model(apk, config)
    with pytest.raises(ValueError, match="missing=.*inactive"):
        verify_apk(apk, config, profile="debug")
    entries = apk_entries(apk)
    entries[inactive] = b"inactive"
    rewrite_apk(apk, entries)
    assert verify_apk(apk, config, profile="debug") == (4, 6)
    with pytest.raises(ValueError, match="unexpected="):
        verify_apk(apk, config, profile="release")


@pytest.mark.parametrize("mutation", ["comparison_hash", "missing_comparison", "unregistered", "forbidden", "runtime_shadow"])
def test_debug_profile_does_not_allow_arbitrary_or_mutated_assets(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, mutation: str) -> None:
    apk, config_path = write_debug_fixture(tmp_path, monkeypatch)
    entries = apk_entries(apk)
    comparison = next(iter(checker.DEBUG_COMPARISON_MODELS))
    message = "model asset set mismatch"
    if mutation == "comparison_hash":
        entries[comparison] = b"changed"
        message = "SHA-256 mismatch"
    elif mutation == "missing_comparison":
        del entries[comparison]
    elif mutation == "unregistered":
        entries["assets/models/another-comparison.tflite"] = b"unregistered"
    elif mutation == "forbidden":
        entries["assets/logs/fixture.json"] = b"{}"
        message = "forbidden"
    else:
        config = json.loads(config_path.read_text())
        config["models"]["shadow"] = {"asset": comparison.removeprefix("assets/")}
        config_path.write_text(json.dumps(config))
        entries["assets/model-config/two_model_runtime.json"] = config_path.read_bytes()
        message = "debug comparison model is also declared"
    rewrite_apk(apk, entries)
    with pytest.raises(ValueError, match=message):
        verify_apk(apk, config_path, profile="debug")


def test_cli_default_debug_and_explicit_release_are_distinct(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    apk, config = write_debug_fixture(tmp_path, monkeypatch)
    companion = tmp_path / "companion.json"
    companion.write_text(json.dumps({"asset": "models/companion.tflite", "sha256": hashlib.sha256(b"sam").hexdigest()}))
    entries = apk_entries(apk)
    entries["assets/model-config/companion.json"] = companion.read_bytes()
    entries["assets/models/companion.tflite"] = b"sam"
    rewrite_apk(apk, entries)
    monkeypatch.setattr(checker, "DEFAULT_APK", apk)
    monkeypatch.setattr(checker, "DEFAULT_CONFIG", config)
    monkeypatch.setattr(checker, "DEFAULT_COMPANION_CONTRACTS", (companion,))
    monkeypatch.setattr("sys.argv", ["checker"])
    assert checker.main() == 0
    assert "PASS (debug)" in capsys.readouterr().out
    monkeypatch.setattr("sys.argv", ["checker", "--profile", "release"])
    assert checker.main() == 1
    assert "unexpected=" in capsys.readouterr().err


@pytest.mark.parametrize("profile", ["debug", "release"])
@pytest.mark.parametrize("mutation", ["runtime_bytes", "companion_bytes", "primary_hash", "zip_duplicate"])
def test_both_profiles_preserve_config_hash_and_zip_guards(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, profile: str, mutation: str) -> None:
    apk, config = write_debug_fixture(tmp_path, monkeypatch) if profile == "debug" else write_fixture(tmp_path)
    companion = tmp_path / "companion.json"
    companion.write_text(json.dumps({"asset": "models/companion.tflite", "sha256": hashlib.sha256(b"sam").hexdigest()}))
    entries = apk_entries(apk)
    companion_entry = "assets/model-config/companion.json"
    entries[companion_entry] = companion.read_bytes()
    entries["assets/models/companion.tflite"] = b"sam"
    rewrite_apk(apk, entries)
    assert verify_apk(apk, config, (companion,), profile=profile)[0] == (4 if profile == "debug" else 2)
    if mutation == "runtime_bytes":
        entries["assets/model-config/two_model_runtime.json"] += b" "
        message = "runtime config differs"
    elif mutation == "companion_bytes":
        entries[companion_entry] += b" "
        message = "companion contract differs"
    elif mutation == "primary_hash":
        entries["assets/models/walksafe.tflite"] = b"changed"
        message = "SHA-256 mismatch"
    else:
        with pytest.warns(UserWarning, match="Duplicate name"):
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("assets/models/walksafe.tflite", b"model")
        message = "duplicate ZIP entry names"
    if mutation != "zip_duplicate":
        rewrite_apk(apk, entries)
    with pytest.raises(ValueError, match=message):
        verify_apk(apk, config, (companion,), profile=profile)


def test_runner_selects_explicit_apk_model_profiles() -> None:
    source = (checker.ROOT / "scripts/run_walksafe_test_layers_current.sh").read_text()
    debug_check = source.index('check_android_apk_model_asset_20260713.py" --profile debug')
    release_build = source.index(":app:verifyReleaseBuildConfigIsolation :app:lintRelease :app:assembleRelease")
    release_check = source.index('--profile release \\\n    --apk "${REPO_ROOT}/apps/android/app/build/outputs/apk/release/app-release-unsigned.apk"')
    assert debug_check < release_build < release_check
