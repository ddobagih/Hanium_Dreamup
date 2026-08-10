from __future__ import annotations

import base64
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

from scripts import build_walksafe_w3_engineering_evidence_20260726 as builder


def write(path: Path, content: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content, encoding="utf-8")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class W3EngineeringEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def make_fixture(self, name: str = "case") -> tuple[Path, Path, Path]:
        repo = self.root / name / "repo"
        run_dir = repo / ".w3-raw-run"
        output_dir = repo / ".w3-output"

        source_files = {
            "apps/android/app/src/main/App.kt": "class App\n",
            "apps/android/adminapp/src/main/Admin.kt": "class Admin\n",
            "apps/android-gateway/src/index.ts": "export const gateway = true;\n",
            "apps/android-gateway/package.json": '{"name":"walksafe-android-gateway","version":"0.1.0"}\n',
            "backend/app/main.py": "APP = 'walksafe'\n",
            "model/runtime.py": "MODEL = 'candidate'\n",
            "configs/runtime.json": '{"mode":"internal"}\n',
            "apps/android/app/src/main/assets/model-config/runtime.json": '{"model":"test"}\n',
            "apps/android/app/src/main/assets/models/model.tflite": b"model-bytes",
            "contracts/fixtures/walking-route-v1.json": '{"route":[]}\n',
            "tests/fixtures/model_registry_dataset/image.fixture": b"fixture-image",
        }
        for relative, content in source_files.items():
            write(repo / relative, content)

        write(
            repo / "apps/android/app/gradle.lockfile",
            "com.example:user-lib:1.0=releaseRuntimeClasspath\n",
        )
        write(
            repo / "apps/android/adminapp/gradle.lockfile",
            "com.example:admin-lib:2.0=releaseRuntimeClasspath\n",
        )
        write(
            repo / "apps/android/gradle/verification-metadata.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<verification-metadata>
  <components>
    <component group="com.example" name="user-lib" version="1.0">
      <artifact name="user-lib.jar"><sha256 value="%s"/></artifact>
      <artifact name="user-lib.module"><sha256 value="%s"/></artifact>
    </component>
    <component group="com.example" name="admin-lib" version="2.0">
      <artifact name="admin-lib.jar"><sha256 value="%s"/></artifact>
    </component>
  </components>
</verification-metadata>
"""
            % ("a" * 64, "d" * 64, "b" * 64),
        )
        write(
            repo / "apps/android-gateway/package-lock.json",
            json.dumps(
                {
                    "name": "gateway",
                    "lockfileVersion": 3,
                    "packages": {
                        "": {"name": "gateway", "version": "0.1.0"},
                        "node_modules/typescript": {
                            "version": "6.0.3",
                            "integrity": "sha512-"
                            + base64.b64encode(b"n" * 64).decode("ascii"),
                            "dev": True,
                        },
                    },
                }
            ),
        )
        write(
            repo / "backend/requirements.lock",
            "fastapi==1.2.3 \\\n"
            f"    --hash=sha256:{'c' * 64}\n",
        )
        canonical_register = (
            Path(builder.__file__).resolve().parents[1] / builder.FORMAL_TEST_REGISTER
        )
        target_register = repo / builder.FORMAL_TEST_REGISTER
        target_register.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(canonical_register, target_register)
        generator_source = Path(builder.__file__).read_bytes()
        write(repo / builder.GENERATOR_RELATIVE_PATH, generator_source)

        for name, entry in (
            ("user-app-internal.apk", ("classes.dex", b"user-dex")),
            ("admin-app-internal.apk", ("classes.dex", b"admin-dex")),
        ):
            path = run_dir / "artifacts" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
                archive.writestr(*entry)

        gateway_archive = run_dir / "artifacts/android-gateway-dist.tar.gz"
        gateway_archive.parent.mkdir(parents=True, exist_ok=True)
        archive_files = {
            "package.json": (repo / "apps/android-gateway/package.json").read_bytes(),
            "package-lock.json": (
                repo / "apps/android-gateway/package-lock.json"
            ).read_bytes(),
            "dist/server.js": b"console.log('gateway');\n",
            "dist/src/routes.js": b"export const routes = [];\n",
        }
        with tarfile.open(gateway_archive, "w:gz") as archive:
            for relative, data in archive_files.items():
                info = tarfile.TarInfo(relative)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))

        for subject_id in ("backend", "model", "config"):
            payload = builder.build_subject_payload(repo, subject_id)
            write(
                run_dir / f"subjects/{subject_id}.json",
                json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            )

        commands = [
            {
                "command_id": "android-internal-build",
                "role": "ANDROID_INTERNAL_BUILD",
                "argv": ["./gradlew", ":app:assembleDebug", ":adminapp:assembleDebug"],
                "cwd": "apps/android",
                "tool_version": "Gradle fixture 1",
                "start": "2026-07-26T01:00:00+09:00",
                "end": "2026-07-26T01:01:00+09:00",
                "exit_code": 0,
                "log_path": "logs/android-internal-build.log",
                "log_sha256": "",
                "output_paths": [],
            },
            {
                "command_id": "android-artifact-stage",
                "role": "ANDROID_ARTIFACT_STAGE",
                "argv": [
                    "python3",
                    "-B",
                    "-c",
                    builder.ANDROID_STAGE_PROGRAM,
                    run_dir.relative_to(repo).as_posix(),
                ],
                "cwd": ".",
                "tool_version": "Python fixture 1",
                "start": "2026-07-26T01:01:00+09:00",
                "end": "2026-07-26T01:02:00+09:00",
                "exit_code": 0,
                "log_path": "logs/android-artifact-stage.log",
                "log_sha256": "",
                "output_paths": [
                    "artifacts/user-app-internal.apk",
                    "artifacts/admin-app-internal.apk",
                ],
            },
            {
                "command_id": "gateway-internal-build",
                "role": "GATEWAY_INTERNAL_BUILD",
                "argv": ["npm", "run", "build"],
                "cwd": "apps/android-gateway",
                "tool_version": "npm fixture 1",
                "start": "2026-07-26T01:02:00+09:00",
                "end": "2026-07-26T01:03:00+09:00",
                "exit_code": 0,
                "log_path": "logs/gateway-internal-build.log",
                "log_sha256": "",
                "output_paths": [],
            },
            {
                "command_id": "gateway-artifact-package",
                "role": "GATEWAY_ARTIFACT_PACKAGE",
                "argv": [
                    "python3",
                    "-B",
                    "-c",
                    builder.GATEWAY_PACKAGE_PROGRAM,
                    run_dir.relative_to(repo).as_posix(),
                ],
                "cwd": ".",
                "tool_version": "Python fixture 1",
                "start": "2026-07-26T01:03:00+09:00",
                "end": "2026-07-26T01:04:00+09:00",
                "exit_code": 0,
                "log_path": "logs/gateway-artifact-package.log",
                "log_sha256": "",
                "output_paths": ["artifacts/android-gateway-dist.tar.gz"],
            },
            {
                "command_id": "source-subject-capture",
                "role": "SOURCE_SUBJECT_CAPTURE",
                "argv": [
                    "python3",
                    "-B",
                    "-c",
                    builder.SUBJECT_CAPTURE_PROGRAM,
                    run_dir.relative_to(repo).as_posix(),
                ],
                "cwd": ".",
                "tool_version": "Python fixture 1",
                "start": "2026-07-26T01:04:00+09:00",
                "end": "2026-07-26T01:05:00+09:00",
                "exit_code": 0,
                "log_path": "logs/source-subject-capture.log",
                "log_sha256": "",
                "output_paths": [
                    "subjects/backend.json",
                    "subjects/model.json",
                    "subjects/config.json",
                ],
            },
        ]
        for command in commands:
            log = run_dir / command["log_path"]
            write(log, f"{command['command_id']}: PASS\n")
            command["log_sha256"] = sha256(log)
        receipt = builder.build_command_receipt_payload(commands)
        write(
            run_dir / "command-receipt.json",
            json.dumps(receipt, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        )
        return repo, run_dir, output_dir

    def reseal_receipt(self, run_dir: Path, mutate) -> None:
        path = run_dir / "command-receipt.json"
        payload = json.loads(path.read_text())
        mutate(payload)
        payload = builder.seal_payload(payload)
        write(path, json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n")

    def refresh_subject(self, repo: Path, run_dir: Path, subject_id: str) -> None:
        payload = builder.build_subject_payload(repo, subject_id)
        write(
            run_dir / f"subjects/{subject_id}.json",
            json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        )

    def rewrite_apk(self, path: Path, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            for relative, content in entries.items():
                archive.writestr(relative, content)

    def rewrite_gateway_archive(
        self, repo: Path, path: Path, *, omit: str | None = None
    ) -> None:
        entries = {
            "package.json": (repo / "apps/android-gateway/package.json").read_bytes(),
            "package-lock.json": (
                repo / "apps/android-gateway/package-lock.json"
            ).read_bytes(),
            "dist/server.js": b"server\n",
            "dist/src/routes.js": b"routes\n",
        }
        with tarfile.open(path, "w:gz") as archive:
            for relative, content in entries.items():
                if relative == omit:
                    continue
                info = tarfile.TarInfo(relative)
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))

    def test_write_and_check_generate_exact_internal_evidence(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        written = builder.run("write", repo, run_dir, output_dir)
        checked = builder.run("check", repo, run_dir, output_dir)

        self.assertEqual(written["result"], "PASS")
        self.assertEqual(checked["output_count"], 8)
        source = json.loads((output_dir / "source-snapshot.json").read_text())
        modules = json.loads((output_dir / "module-inventory.json").read_text())
        fixtures = json.loads((output_dir / "fixture-inventory.json").read_text())
        provenance = json.loads(
            (output_dir / "internal-build-provenance.json").read_text()
        )
        spdx = json.loads((output_dir / "sbom.spdx.json").read_text())
        cyclonedx = json.loads((output_dir / "sbom.cyclonedx.json").read_text())

        self.assertIsNone(source["source_commit"])
        self.assertEqual(modules["summary"]["duplicate_ownership_count"], 0)
        self.assertEqual(fixtures["summary"]["formal_test_count"], 279)
        self.assertTrue(fixtures["summary"]["all_formal_tests_not_run"])
        self.assertFalse(fixtures["summary"]["all_fixture_assignments_complete"])
        self.assertEqual(
            fixtures["formal_test_register"]["sha256"],
            builder.CANONICAL_FORMAL_TEST_REGISTER_SHA256,
        )
        self.assertFalse(provenance["claim_boundary"]["release_eligible"])
        self.assertEqual(provenance["claim_boundary"]["signing_status"], "NOT_ASSESSED")
        artifacts = {
            item["artifact_id"]: item for item in provenance["build_subjects"]
        }
        self.assertEqual(
            artifacts["USER_APK"]["produced_by_command_id"],
            "android-artifact-stage",
        )
        self.assertEqual(
            artifacts["GATEWAY_DIST_ARCHIVE"]["produced_by_command_id"],
            "gateway-artifact-package",
        )
        self.assertNotIn("integrity", spdx)
        self.assertNotIn("walksafe_profile", spdx)
        self.assertNotIn("integrity", cyclonedx)
        self.assertEqual(
            set(json.loads(spdx["annotations"][0]["comment"])["component_scopes"]),
            {"user", "admin", "gateway", "backend", "model-config"},
        )
        cyclone_scopes = {
            prop["value"]
            for component in cyclonedx["components"]
            for prop in component["properties"]
            if prop["name"] == "walksafe:component-scope"
        }
        self.assertEqual(
            cyclone_scopes, {"user", "admin", "gateway", "backend", "model-config"}
        )
        maven_spdx = next(
            item for item in spdx["packages"] if item["name"] == "com.example:user-lib"
        )
        self.assertNotIn("checksums", maven_spdx)
        self.assertIn("user-lib.jar", maven_spdx["comment"])
        self.assertIn("a" * 64, maven_spdx["comment"])
        maven_cyclonedx = next(
            item
            for item in cyclonedx["components"]
            if item["name"] == "com.example:user-lib"
        )
        self.assertNotIn("hashes", maven_cyclonedx)
        self.assertTrue(
            any(
                "user-lib.jar" in prop["name"] and prop["value"] == "a" * 64
                for prop in maven_cyclonedx["properties"]
            )
        )
        lock_inventory = json.loads((output_dir / "lock-inventory.json").read_text())
        user_dependency = next(
            item
            for item in lock_inventory["dependencies"]
            if item.get("coordinate") == "com.example:user-lib:1.0"
        )
        self.assertEqual(
            {item["artifact"] for item in user_dependency["verification_artifacts"]},
            {"user-lib.jar", "user-lib.module"},
        )
        self.assertEqual(user_dependency["verification_status"], "HASHED")
        self.assertTrue(
            all(
                item["verification_status"] == "HASHED"
                for item in lock_inventory["dependencies"]
                if item["ecosystem"] == "maven"
            )
        )
        npm_dependency = next(
            item
            for item in lock_inventory["dependencies"]
            if item["ecosystem"] == "npm"
        )
        self.assertEqual(npm_dependency["name"], "typescript")
        self.assertEqual(
            npm_dependency["installation_path"], "node_modules/typescript"
        )
        manifest = json.loads(
            (output_dir / "engineering-evidence-manifest.json").read_text()
        )
        official_schema = manifest["validation_summary"][
            "official_standard_schema_validation"
        ]
        self.assertEqual(
            official_schema["status"],
            "STANDARD_SCHEMA_VALIDATION_REQUIRED_NOT_RUN",
        )
        self.assertFalse(official_schema["local_validator_or_schema_available"])
        self.assertFalse(official_schema["completion_allowed"])
        self.assertEqual(
            official_schema["required_heavy_execution_evidence"],
            [
                "official-spdx-validator-result.json",
                "official-spdx-validator.log",
                "official-cyclonedx-validator-result.json",
                "official-cyclonedx-validator.log",
            ],
        )
        self.assertFalse(
            official_schema["current_run_contract_contains_required_evidence"]
        )
        self.assertEqual(
            manifest["claim_boundary"]["dev19_completion_status"],
            "BLOCKED_PENDING_OFFICIAL_STANDARD_SCHEMA_VALIDATOR_RESULTS",
        )
        output_records = {item["path"]: item for item in manifest["outputs"]}
        self.assertEqual(
            output_records["sbom.spdx.json"]["sha256"],
            sha256(output_dir / "sbom.spdx.json"),
        )
        self.assertEqual(
            output_records["sbom.cyclonedx.json"]["sha256"],
            sha256(output_dir / "sbom.cyclonedx.json"),
        )
        generated_text = "\n".join(
            (output_dir / name).read_text() for name in builder.OUTPUT_FILENAMES
        )
        self.assertNotIn("UN" + "SIGNED", generated_text.upper())

    def test_fixed_stage_and_package_programs_run_on_fixture_outputs(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(
            repo / "apps/android/app/build/outputs/apk/debug/app-debug.apk",
            (run_dir / "artifacts/user-app-internal.apk").read_bytes(),
        )
        write(
            repo
            / "apps/android/adminapp/build/outputs/apk/debug/adminapp-debug.apk",
            (run_dir / "artifacts/admin-app-internal.apk").read_bytes(),
        )
        write(repo / "apps/android-gateway/dist/server.js", "server\n")
        write(repo / "apps/android-gateway/dist/src/routes.js", "routes\n")
        run_argument = run_dir.relative_to(repo).as_posix()

        subprocess.run(
            [
                sys.executable,
                "-B",
                "-c",
                builder.ANDROID_STAGE_PROGRAM,
                run_argument,
            ],
            cwd=repo,
            check=True,
        )
        subprocess.run(
            [
                sys.executable,
                "-B",
                "-c",
                builder.GATEWAY_PACKAGE_PROGRAM,
                run_argument,
            ],
            cwd=repo,
            check=True,
        )
        builder.run("write", repo, run_dir, output_dir)

    def test_check_rejects_tampered_generated_json(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        builder.run("write", repo, run_dir, output_dir)
        path = output_dir / "internal-build-provenance.json"
        payload = json.loads(path.read_text())
        payload["claim_boundary"]["deployment_performed"] = True
        write(path, json.dumps(payload))

        with self.assertRaisesRegex(builder.EvidenceError, "fingerprint mismatch"):
            builder.run("check", repo, run_dir, output_dir)

    def test_check_rejects_missing_admin_gateway_subject_and_log(self) -> None:
        cases = (
            ("admin", "artifacts/admin-app-internal.apk"),
            ("gateway", "artifacts/android-gateway-dist.tar.gz"),
            ("subject", "subjects/model.json"),
            ("log", "logs/gateway-internal-build.log"),
        )
        for name, relative in cases:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"missing-{name}")
                builder.run("write", repo, run_dir, output_dir)
                (run_dir / relative).unlink()
                with self.assertRaises(builder.EvidenceError):
                    builder.run("check", repo, run_dir, output_dir)

    def test_check_rejects_raw_log_hash_mismatch(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        builder.run("write", repo, run_dir, output_dir)
        write(run_dir / "logs/android-internal-build.log", "tampered\n")

        with self.assertRaisesRegex(builder.EvidenceError, "log SHA-256 mismatch"):
            builder.run("check", repo, run_dir, output_dir)

    def test_rejects_raw_path_swap_between_lstat_and_open(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        target = run_dir / "logs/android-internal-build.log"
        replacement = self.root / "replacement-raw.log"
        replacement.write_bytes(target.read_bytes())
        original_open = builder.os.open
        swapped = False

        def swap_before_open(path, flags, mode=0o777, *, dir_fd=None):
            nonlocal swapped
            if not swapped and Path(path) == target:
                os.replace(replacement, target)
                swapped = True
            if dir_fd is None:
                return original_open(path, flags, mode)
            return original_open(path, flags, mode, dir_fd=dir_fd)

        builder.os.open = swap_before_open
        try:
            with self.assertRaisesRegex(
                builder.EvidenceError, "file path changed before stable read"
            ):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder.os.open = original_open
        self.assertTrue(swapped)

    def test_check_rejects_duplicate_module_ownership_after_reseal(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        builder.run("write", repo, run_dir, output_dir)
        path = output_dir / "module-inventory.json"
        payload = json.loads(path.read_text())
        duplicate = dict(payload["modules"][0]["source_files"][0])
        payload["modules"][1]["source_files"].append(duplicate)
        payload["modules"][1]["source_file_count"] += 1
        payload = builder.seal_payload(payload)
        write(path, json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n")

        with self.assertRaisesRegex(builder.EvidenceError, "duplicate ownership"):
            builder.run("check", repo, run_dir, output_dir)

    def test_standard_sboms_reject_custom_top_level_fields(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        builder.run("write", repo, run_dir, output_dir)
        for filename in ("sbom.spdx.json", "sbom.cyclonedx.json"):
            with self.subTest(filename=filename):
                payload = json.loads((output_dir / filename).read_text())
                payload["integrity"] = {"forged": True}
                with self.assertRaisesRegex(
                    builder.EvidenceError, "top-level allowed/required"
                ):
                    builder._validate_generated_payload(filename, payload)

    def test_rejects_short_npm_sha512_sri(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        path = repo / "apps/android-gateway/package-lock.json"
        payload = json.loads(path.read_text())
        payload["packages"]["node_modules/typescript"]["integrity"] = "sha512-YQ=="
        write(path, json.dumps(payload))

        with self.assertRaisesRegex(builder.EvidenceError, "SRI digest length"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_empty_or_duplicate_npm_sri_token_lists(self) -> None:
        cases = (
            ("empty", "   ", "token list is empty"),
            (
                "duplicate",
                " ".join(
                    [
                        "sha512-" + base64.b64encode(b"n" * 64).decode(),
                        "sha512-" + base64.b64encode(b"n" * 64).decode(),
                    ]
                ),
                "duplicate .*SRI",
            ),
        )
        for name, integrity, message in cases:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"npm-sri-{name}")
                path = repo / "apps/android-gateway/package-lock.json"
                payload = json.loads(path.read_text())
                payload["packages"]["node_modules/typescript"][
                    "integrity"
                ] = integrity
                write(path, json.dumps(payload))
                with self.assertRaisesRegex(builder.EvidenceError, message):
                    builder.run("write", repo, run_dir, output_dir)

    def test_accepts_scoped_and_nested_npm_installation_paths(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        path = repo / "apps/android-gateway/package-lock.json"
        payload = json.loads(path.read_text())
        payload["packages"]["node_modules/@scope/pkg"] = {
            "version": "1.0.0",
            "integrity": "sha512-" + base64.b64encode(b"s" * 64).decode(),
        }
        payload["packages"]["node_modules/a/node_modules/@nested/pkg"] = {
            "version": "2.0.0",
            "integrity": "sha512-" + base64.b64encode(b"s" * 64).decode(),
        }
        write(path, json.dumps(payload))
        self.rewrite_gateway_archive(
            repo, run_dir / "artifacts/android-gateway-dist.tar.gz"
        )

        builder.run("write", repo, run_dir, output_dir)
        inventory = json.loads((output_dir / "lock-inventory.json").read_text())
        npm_items = {
            item["installation_path"]: item
            for item in inventory["dependencies"]
            if item["ecosystem"] == "npm"
        }
        self.assertEqual(npm_items["node_modules/@scope/pkg"]["name"], "@scope/pkg")
        self.assertEqual(
            npm_items["node_modules/a/node_modules/@nested/pkg"]["name"],
            "@nested/pkg",
        )

    def test_rejects_malformed_npm_installation_paths(self) -> None:
        malformed_paths = (
            "node_modules/@scope",
            "node_modules/@_scope/pkg",
            "node_modules/@scope/_pkg",
            "node_modules/@scope/.pkg",
            "./node_modules/pkg",
            "node_modules//pkg",
            "node_modules/pkg/",
            "node_modules/./pkg",
            "node_modules/a/../pkg",
            "a/b",
        )
        for index, malformed in enumerate(malformed_paths):
            with self.subTest(path=malformed):
                repo, run_dir, output_dir = self.make_fixture(
                    f"npm-malformed-path-{index}"
                )
                path = repo / "apps/android-gateway/package-lock.json"
                payload = json.loads(path.read_text())
                payload["packages"][malformed] = {
                    "version": "1.0.0",
                    "integrity": "sha512-"
                    + base64.b64encode(bytes([index + 1]) * 64).decode(),
                }
                write(path, json.dumps(payload))
                with self.assertRaisesRegex(
                    builder.EvidenceError, "installation path"
                ):
                    builder.run("write", repo, run_dir, output_dir)

    def test_rejects_unpinned_python_lock_noncomment_line(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(
            repo / "backend/requirements.lock",
            f"fastapi==1.2.3 \\\n    --hash=sha256:{'c' * 64}\n--index-url https://example.invalid\n",
        )
        self.refresh_subject(repo, run_dir, "backend")

        with self.assertRaisesRegex(builder.EvidenceError, "logical requirement"):
            builder.run("write", repo, run_dir, output_dir)

    def test_accepts_python_extras_and_environment_marker(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(
            repo / "backend/requirements.lock",
            (
                'FastAPI[standard,test]==1.2.3; python_version >= "3.11" \\\n'
                f"    --hash=sha256:{'c' * 64}\n"
            ),
        )
        self.refresh_subject(repo, run_dir, "backend")

        builder.run("write", repo, run_dir, output_dir)
        inventory = json.loads((output_dir / "lock-inventory.json").read_text())
        dependency = next(
            item
            for item in inventory["dependencies"]
            if item["ecosystem"] == "pypi"
        )
        self.assertEqual(dependency["name"], "FastAPI")
        self.assertEqual(dependency["extras"], ["standard", "test"])
        self.assertEqual(dependency["marker"], 'python_version >= "3.11"')
        self.assertEqual(dependency["version"], "1.2.3")
        self.assertEqual(dependency["sha256"], ["c" * 64])

    def test_rejects_attached_or_duplicate_python_hashes(self) -> None:
        cases = (
            (
                "attached",
                f"foo==1.0--hash=sha256:{'a' * 64}\n",
                "logical requirement",
            ),
            (
                "duplicate",
                (
                    f"foo==1.0 --hash=sha256:{'a' * 64} "
                    f"--hash=sha256:{'a' * 64}\n"
                ),
                "duplicate .*hash",
            ),
        )
        for name, content, message in cases:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"python-{name}")
                write(repo / "backend/requirements.lock", content)
                self.refresh_subject(repo, run_dir, "backend")
                with self.assertRaisesRegex(builder.EvidenceError, message):
                    builder.run("write", repo, run_dir, output_dir)

    def test_allows_same_hash_for_distinct_python_requirements(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(
            repo / "backend/requirements.lock",
            (
                f"foo==1.0 --hash=sha256:{'a' * 64}\n"
                f"bar==2.0 --hash=sha256:{'a' * 64}\n"
            ),
        )
        self.refresh_subject(repo, run_dir, "backend")

        builder.run("write", repo, run_dir, output_dir)
        inventory = json.loads((output_dir / "lock-inventory.json").read_text())
        self.assertEqual(
            {
                item["name"]
                for item in inventory["dependencies"]
                if item["ecosystem"] == "pypi"
            },
            {"foo", "bar"},
        )

    def test_rejects_noncanonical_formal_register(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        path = repo / builder.FORMAL_TEST_REGISTER
        payload = json.loads(path.read_text())
        payload["test_cases"][0]["test_case_id"] = "TC-FORGED-001"
        write(path, json.dumps(payload, ensure_ascii=False))

        with self.assertRaisesRegex(builder.EvidenceError, "canonical SHA-256"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_duplicate_fixture_alias(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(repo / "other/fixtures/walking-route-v1.json", "{}\n")

        with self.assertRaisesRegex(builder.EvidenceError, "duplicate fixture alias"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_receipt_extra_top_level_field(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        self.reseal_receipt(run_dir, lambda payload: payload.__setitem__("extra", True))

        with self.assertRaisesRegex(builder.EvidenceError, "top-level fields"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_boolean_exit_code(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        self.reseal_receipt(
            run_dir,
            lambda payload: payload["commands"][0].__setitem__("exit_code", False),
        )

        with self.assertRaisesRegex(builder.EvidenceError, "integer zero"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_reversed_build_stage_chronology(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()

        def reverse_stage(payload):
            payload["commands"][1]["start"] = "2026-07-26T00:58:00+09:00"
            payload["commands"][1]["end"] = "2026-07-26T00:59:00+09:00"

        self.reseal_receipt(run_dir, reverse_stage)
        with self.assertRaisesRegex(builder.EvidenceError, "chronology"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_arbitrary_command_producer_and_role(self) -> None:
        mutations = (
            (
                "producer",
                lambda payload: payload["commands"][0].__setitem__(
                    "command_id", "arbitrary-producer"
                ),
                "unapproved command producer",
            ),
            (
                "role",
                lambda payload: payload["commands"][0].__setitem__(
                    "role", "RELEASE_BUILD"
                ),
                "role differs",
            ),
            (
                "argv",
                lambda payload: payload["commands"][0].__setitem__("argv", ["true"]),
                "argv differs",
            ),
        )
        for name, mutate, message in mutations:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"receipt-{name}")
                self.reseal_receipt(run_dir, mutate)
                with self.assertRaisesRegex(builder.EvidenceError, message):
                    builder.run("write", repo, run_dir, output_dir)

    def test_rejects_symlink_component_in_command_cwd(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        android = repo / "apps/android"
        real_android = repo / "apps/android-real"
        android.rename(real_android)
        android.symlink_to(real_android, target_is_directory=True)

        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_original_repo_run_and_output_path_symlinks(self) -> None:
        repo, run_dir, output_dir = self.make_fixture("original-repo-link")
        repo_link = self.root / "repo-leaf-link"
        repo_link.symlink_to(repo, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo_link, run_dir, output_dir)

        repo_parent_link = self.root / "repo-parent-link"
        repo_parent_link.symlink_to(repo.parent, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo_parent_link / repo.name, run_dir, output_dir)

        traversal_target = self.root / "traversal-target"
        traversal_target.mkdir()
        traversal_link = self.root / "traversal-link"
        traversal_link.symlink_to(traversal_target, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run(
                "write",
                traversal_link / ".." / repo.name,
                run_dir,
                output_dir,
            )

        repo, run_dir, output_dir = self.make_fixture("original-run-link")
        run_link = self.root / "run-leaf-link"
        run_link.symlink_to(run_dir, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo, run_link, output_dir)

        run_parent_link = self.root / "run-parent-link"
        run_parent_link.symlink_to(run_dir.parent, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo, run_parent_link / run_dir.name, output_dir)

        repo, run_dir, _output_dir = self.make_fixture("original-output-link")
        real_output = self.root / "real-output"
        real_output.mkdir()
        output_leaf_link = self.root / "output-leaf-link"
        output_leaf_link.symlink_to(real_output, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo, run_dir, output_leaf_link)

        real_parent = self.root / "real-output-parent"
        real_parent.mkdir()
        parent_link = self.root / "output-parent-link"
        parent_link.symlink_to(real_parent, target_is_directory=True)
        with self.assertRaisesRegex(builder.EvidenceError, "symlink"):
            builder.run("write", repo, run_dir, parent_link / "generated")

    def test_rejects_apk_without_manifest_or_dex(self) -> None:
        mutations = (
            ("manifest", {"classes.dex": b"dex"}, "AndroidManifest"),
            ("dex", {"AndroidManifest.xml": b"manifest"}, r"classes\*\.dex"),
            (
                "empty-manifest",
                {"AndroidManifest.xml": b"", "classes.dex": b"dex"},
                "AndroidManifest.xml is empty",
            ),
            (
                "empty-dex",
                {"AndroidManifest.xml": b"manifest", "classes.dex": b""},
                r"classes\*\.dex is empty",
            ),
        )
        for name, entries, message in mutations:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"apk-{name}")
                self.rewrite_apk(
                    run_dir / "artifacts/user-app-internal.apk",
                    entries,
                )
                with self.assertRaisesRegex(builder.EvidenceError, message):
                    builder.run("write", repo, run_dir, output_dir)

    def test_rejects_apk_zip_symlink_entry(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        path = run_dir / "artifacts/user-app-internal.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex")
            link = zipfile.ZipInfo("assets/link")
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(link, "target")

        with self.assertRaisesRegex(builder.EvidenceError, "APK symlink entry"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_gateway_archive_without_required_runtime_file(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        self.rewrite_gateway_archive(
            repo,
            run_dir / "artifacts/android-gateway-dist.tar.gz",
            omit="dist/src/routes.js",
        )

        with self.assertRaisesRegex(builder.EvidenceError, "required files missing"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_empty_gateway_runtime_file(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        archive_path = run_dir / "artifacts/android-gateway-dist.tar.gz"
        entries = {
            "package.json": (repo / "apps/android-gateway/package.json").read_bytes(),
            "package-lock.json": (
                repo / "apps/android-gateway/package-lock.json"
            ).read_bytes(),
            "dist/server.js": b"",
            "dist/src/routes.js": b"routes\n",
        }
        with tarfile.open(archive_path, "w:gz") as archive:
            for relative, content in entries.items():
                info = tarfile.TarInfo(relative)
                info.size = len(content)
                archive.addfile(info, io.BytesIO(content))

        with self.assertRaisesRegex(builder.EvidenceError, "required file is empty"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_gradle_lock_without_artifact_checksum(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(
            repo / "apps/android/gradle/verification-metadata.xml",
            f"""<verification-metadata><components>
<component group="com.example" name="admin-lib" version="2.0">
<artifact name="admin-lib.jar"><sha256 value="{'b' * 64}"/></artifact>
</component>
</components></verification-metadata>
""",
        )

        with self.assertRaisesRegex(builder.EvidenceError, "lacks artifact checksum"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_duplicate_gradle_coordinate(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        line = "com.example:user-lib:1.0=releaseRuntimeClasspath\n"
        write(repo / "apps/android/app/gradle.lockfile", line + line)

        with self.assertRaisesRegex(builder.EvidenceError, "duplicate coordinates"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_invalid_gradle_verification_artifact_hash_sets(self) -> None:
        cases = (
            (
                "missing-sha",
                "<artifact name=\"user-lib.jar\"></artifact>"
                f"<artifact name=\"user-lib.module\"><sha256 value=\"{'d' * 64}\"/></artifact>",
                "exactly one SHA-256",
            ),
            (
                "short-sha",
                "<artifact name=\"user-lib.jar\"><sha256 value=\"abc\"/></artifact>"
                f"<artifact name=\"user-lib.module\"><sha256 value=\"{'d' * 64}\"/></artifact>",
                "hash invalid",
            ),
            (
                "duplicate-name-conflicting",
                f"<artifact name=\"user-lib.jar\"><sha256 value=\"{'a' * 64}\"/></artifact>"
                f"<artifact name=\"user-lib.jar\"><sha256 value=\"{'d' * 64}\"/></artifact>",
                "duplicate Gradle verification artifact name",
            ),
            (
                "duplicate-name-identical",
                f"<artifact name=\"user-lib.jar\"><sha256 value=\"{'a' * 64}\"/></artifact>"
                f"<artifact name=\"user-lib.jar\"><sha256 value=\"{'a' * 64}\"/></artifact>",
                "duplicate Gradle verification artifact name",
            ),
        )
        for name, user_artifacts, message in cases:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"gradle-{name}")
                write(
                    repo / "apps/android/gradle/verification-metadata.xml",
                    (
                        "<verification-metadata><components>"
                        '<component group="com.example" name="user-lib" version="1.0">'
                        f"{user_artifacts}</component>"
                        '<component group="com.example" name="admin-lib" version="2.0">'
                        f'<artifact name="admin-lib.jar"><sha256 value="{"b" * 64}"/></artifact>'
                        "</component></components></verification-metadata>\n"
                    ),
                )
                with self.assertRaisesRegex(builder.EvidenceError, message):
                    builder.run("write", repo, run_dir, output_dir)

    def test_accepts_same_gradle_hash_for_distinct_named_artifacts(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        shared_hash = "a" * 64
        artifact_names = {
            "kotlin-gradle-plugin-api-2.2.10.jar",
            "kotlin-gradle-plugin-api-2.2.10-gradle813.jar",
        }
        artifacts_xml = "".join(
            f'<artifact name="{name}"><sha256 value="{shared_hash}"/></artifact>'
            for name in sorted(artifact_names)
        )
        write(
            repo / "apps/android/gradle/verification-metadata.xml",
            (
                "<verification-metadata><components>"
                '<component group="com.example" name="user-lib" version="1.0">'
                f"{artifacts_xml}</component>"
                '<component group="com.example" name="admin-lib" version="2.0">'
                f'<artifact name="admin-lib.jar"><sha256 value="{"b" * 64}"/></artifact>'
                "</component></components></verification-metadata>\n"
            ),
        )

        builder.run("write", repo, run_dir, output_dir)
        inventory = json.loads((output_dir / "lock-inventory.json").read_text())
        dependency = next(
            item
            for item in inventory["dependencies"]
            if item.get("coordinate") == "com.example:user-lib:1.0"
        )
        self.assertEqual(
            {
                (item["artifact"], item["sha256"])
                for item in dependency["verification_artifacts"]
            },
            {(name, shared_hash) for name in artifact_names},
        )
        cyclonedx = json.loads((output_dir / "sbom.cyclonedx.json").read_text())
        component = next(
            item
            for item in cyclonedx["components"]
            if item["name"] == "com.example:user-lib"
        )
        for artifact_name in artifact_names:
            self.assertTrue(
                any(
                    artifact_name in prop["name"] and prop["value"] == shared_hash
                    for prop in component["properties"]
                )
            )

    def test_reports_unmatched_gradle_verification_coordinates(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        path = repo / "apps/android/gradle/verification-metadata.xml"
        content = path.read_text().replace(
            "</components>",
            (
                '<component group="com.example" name="unused" version="9.9">'
                f'<artifact name="unused.jar"><sha256 value="{"a" * 64}"/></artifact>'
                "</component></components>"
            ),
        )
        write(path, content)

        builder.run("write", repo, run_dir, output_dir)
        inventory = json.loads((output_dir / "lock-inventory.json").read_text())
        unmatched = {
            key: value
            for key, value in inventory["summary"].items()
            if "unmatched_gradle" in key
        }
        self.assertIn(1, unmatched.values())
        self.assertIn(["com.example:unused:9.9"], unmatched.values())

    def test_rejects_non_v3_or_missing_root_npm_lock(self) -> None:
        mutations = (
            (
                "version",
                lambda payload: payload.__setitem__("lockfileVersion", 2),
                "version must be 3",
            ),
            (
                "root",
                lambda payload: payload["packages"].pop(""),
                "root package missing",
            ),
        )
        for name, mutate, message in mutations:
            with self.subTest(name=name):
                repo, run_dir, output_dir = self.make_fixture(f"npm-{name}")
                path = repo / "apps/android-gateway/package-lock.json"
                payload = json.loads(path.read_text())
                mutate(payload)
                write(path, json.dumps(payload))
                with self.assertRaisesRegex(builder.EvidenceError, message):
                    builder.run("write", repo, run_dir, output_dir)

    def test_rejects_pep503_equivalent_python_distribution_names(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        write(
            repo / "backend/requirements.lock",
            (
                f"Foo_Bar==1.0 \\\n    --hash=sha256:{'a' * 64}\n"
                f"foo-bar==1.0 \\\n    --hash=sha256:{'b' * 64}\n"
            ),
        )
        self.refresh_subject(repo, run_dir, "backend")

        with self.assertRaisesRegex(builder.EvidenceError, "duplicate pins"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_repository_change_after_snapshot(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        original = builder._build_module_inventory

        def mutate_after_snapshot(source_by_path, root):
            result = original(source_by_path, root)
            write(root / "backend/app/main.py", "CHANGED = True\n")
            return result

        builder._build_module_inventory = mutate_after_snapshot
        try:
            with self.assertRaisesRegex(builder.EvidenceError, "repository changed"):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder._build_module_inventory = original

    def test_rejects_run_file_added_during_generation(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        original = builder._build_module_inventory

        def add_raw_file(source_by_path, root):
            result = original(source_by_path, root)
            write(run_dir / "logs/late-extra.log", "late\n")
            return result

        builder._build_module_inventory = add_raw_file
        try:
            with self.assertRaisesRegex(builder.EvidenceError, "run directory file set"):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder._build_module_inventory = original

    def test_write_rechecks_all_output_bytes_after_publication(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        original = builder._write_atomic
        write_count = 0

        def mutate_earlier_output(path, content):
            nonlocal write_count
            original(path, content)
            write_count += 1
            if write_count == 2:
                write(output_dir / builder.OUTPUT_FILENAMES[0], "{}\n")

        builder._write_atomic = mutate_earlier_output
        try:
            with self.assertRaisesRegex(
                builder.EvidenceError, "final generated SHA-256"
            ):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder._write_atomic = original

    def test_write_does_not_recapture_final_outputs_as_raw_stamps(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        original = builder._capture_raw_stamps
        captured_output = False

        def observe_stamp_paths(paths):
            nonlocal captured_output
            materialized = tuple(paths)
            captured_output = captured_output or any(
                path.parent == output_dir for path in materialized
            )
            return original(materialized)

        builder._capture_raw_stamps = observe_stamp_paths
        try:
            builder.run("write", repo, run_dir, output_dir)
        finally:
            builder._capture_raw_stamps = original
        self.assertFalse(captured_output)

    def test_write_rejects_output_path_swap_at_stable_read_eof(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        target = output_dir / builder.OUTPUT_FILENAMES[0]
        replacement = self.root / "replacement-output.json"
        write(replacement, "{}\n")
        original_read = builder.os.read
        swapped = False

        def swap_at_eof(descriptor, size):
            nonlocal swapped
            chunk = original_read(descriptor, size)
            if not swapped and not chunk and target.exists():
                opened = os.fstat(descriptor)
                current = target.stat()
                if (opened.st_dev, opened.st_ino) == (
                    current.st_dev,
                    current.st_ino,
                ):
                    os.replace(replacement, target)
                    swapped = True
            return chunk

        builder.os.read = swap_at_eof
        try:
            with self.assertRaisesRegex(
                builder.EvidenceError, "file path changed after stable read"
            ):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder.os.read = original_read
        self.assertTrue(swapped)

    def test_write_rechecks_source_after_output_publication(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        original = builder._write_atomic
        mutated = False

        def mutate_source(path, content):
            nonlocal mutated
            original(path, content)
            if not mutated:
                write(repo / "backend/app/main.py", "changed during publication\n")
                mutated = True

        builder._write_atomic = mutate_source
        try:
            with self.assertRaisesRegex(builder.EvidenceError, "repository changed"):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder._write_atomic = original

    def test_write_stream_rechecks_same_size_source_with_restored_mtime(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        target = repo / "backend/app/main.py"
        original_content = target.read_bytes()
        original_stat = target.stat()
        original_write = builder._write_atomic
        mutated = False

        def mutate_source_without_stat_signal(path, content):
            nonlocal mutated
            original_write(path, content)
            if not mutated:
                changed = bytes([original_content[0] ^ 1]) + original_content[1:]
                target.write_bytes(changed)
                os.utime(
                    target,
                    ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns),
                )
                mutated = True

        builder._write_atomic = mutate_source_without_stat_signal
        try:
            with self.assertRaisesRegex(
                builder.EvidenceError, "repository source SHA-256 changed"
            ):
                builder.run("write", repo, run_dir, output_dir)
        finally:
            builder._write_atomic = original_write
        self.assertTrue(mutated)

    def test_rejects_run_output_ancestry_overlap(self) -> None:
        repo, run_dir, _output_dir = self.make_fixture()

        with self.assertRaisesRegex(builder.EvidenceError, "overlap by ancestry"):
            builder.build_outputs(repo, run_dir, run_dir / "nested-output")

    def test_rejects_extra_run_and_output_files(self) -> None:
        repo, run_dir, output_dir = self.make_fixture("extra-run")
        write(run_dir / "logs/unexpected.log", "extra\n")
        with self.assertRaisesRegex(builder.EvidenceError, "run directory file set"):
            builder.run("write", repo, run_dir, output_dir)

        repo, run_dir, output_dir = self.make_fixture("extra-output")
        builder.run("write", repo, run_dir, output_dir)
        write(output_dir / "unexpected.json", "{}\n")
        with self.assertRaisesRegex(builder.EvidenceError, "unexpected files"):
            builder.run("check", repo, run_dir, output_dir)

    def test_content_set_sorts_and_rejects_duplicate_paths(self) -> None:
        first = {"path": "b", "sha256": "b" * 64, "bytes": 1}
        second = {"path": "a", "sha256": "a" * 64, "bytes": 1}
        self.assertEqual(
            builder._content_set_sha256([first, second]),
            builder._content_set_sha256([second, first]),
        )
        with self.assertRaisesRegex(builder.EvidenceError, "duplicate paths"):
            builder._content_set_sha256([first, dict(first)])

    def test_excludes_only_reserved_w3_direct_child_directories(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        canonical = repo / builder.CANONICAL_W3_EXECUTION_ROOT
        excluded_files = {
            "run-20260726-001/artifacts/sibling.apk",
            "run-20260726-002/logs/sibling.log",
            "evidence-20260726-001/generated.json",
            "aux-execution-20260726-001/logs/execution.log",
        }
        for relative in excluded_files:
            write(canonical / relative, "reserved\n")
        preserved_files = {
            "outside/run-20260726-003/keep.apk",
            (
                f"{builder.CANONICAL_W3_EXECUTION_ROOT.as_posix()}"
                "/evidence/keep.json"
            ),
            (
                f"{builder.CANONICAL_W3_EXECUTION_ROOT.as_posix()}"
                "/nested/run-20260726-004/keep.apk"
            ),
            (
                f"{builder.CANONICAL_W3_EXECUTION_ROOT.as_posix()}"
                "/run-20260726-004-extra/keep.apk"
            ),
            (
                f"{builder.CANONICAL_W3_EXECUTION_ROOT.as_posix()}"
                "/runish-20260726-005/keep.apk"
            ),
        }
        for relative in preserved_files:
            write(repo / relative, "source\n")

        subject_id = "reserved-w3-test"
        builder.SUBJECT_DEFINITIONS[subject_id] = ("docs",)
        try:
            subject = builder.build_subject_payload(repo, subject_id)
        finally:
            del builder.SUBJECT_DEFINITIONS[subject_id]
        subject_paths = {record["path"] for record in subject["paths"]}
        self.assertTrue(preserved_files - {"outside/run-20260726-003/keep.apk"} <= subject_paths)
        self.assertFalse(
            any(
                path.startswith(
                    f"{builder.CANONICAL_W3_EXECUTION_ROOT.as_posix()}/{directory}/"
                )
                for directory in (
                    "run-20260726-001",
                    "run-20260726-002",
                    "evidence-20260726-001",
                    "aux-execution-20260726-001",
                )
                for path in subject_paths
            )
        )

        builder.run("write", repo, run_dir, output_dir)
        snapshot = json.loads((output_dir / "source-snapshot.json").read_text())
        snapshot_paths = {record["path"] for record in snapshot["files"]}
        self.assertTrue(preserved_files <= snapshot_paths)
        self.assertFalse(
            any(
                (
                    canonical
                    / relative.split("/", 1)[0]
                ).relative_to(repo).as_posix()
                in path
                for relative in excluded_files
                for path in snapshot_paths
            )
        )
        for subject_id in ("backend", "model", "config"):
            raw_subject = json.loads(
                (run_dir / f"subjects/{subject_id}.json").read_text()
            )
            self.assertFalse(
                any(
                    path["path"].startswith(
                        f"{builder.CANONICAL_W3_EXECUTION_ROOT.as_posix()}/"
                    )
                    for path in raw_subject["paths"]
                )
            )

    def test_reserved_w3_direct_child_symlink_is_not_hidden(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        canonical = repo / builder.CANONICAL_W3_EXECUTION_ROOT
        canonical.mkdir(parents=True, exist_ok=True)
        (canonical / "run-20260726-001").symlink_to(
            repo / "model",
            target_is_directory=True,
        )

        with self.assertRaisesRegex(builder.EvidenceError, "source directory symlink"):
            builder.run("write", repo, run_dir, output_dir)

    def test_rejects_symlink_before_generated_directory_exclusion(self) -> None:
        repo, run_dir, output_dir = self.make_fixture()
        (repo / "build").symlink_to(repo / "model", target_is_directory=True)

        with self.assertRaisesRegex(builder.EvidenceError, "source directory symlink"):
            builder.run("write", repo, run_dir, output_dir)


if __name__ == "__main__":
    unittest.main()
