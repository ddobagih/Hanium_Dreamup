import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/check_android_depth_scaffold_20260531.py"
SPEC = importlib.util.spec_from_file_location(SCRIPT.stem, SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_current_android_depth_scaffold_passes(capsys) -> None:
    MODULE.main()

    assert "PASS: android ARCore depth scaffold static checks passed" in capsys.readouterr().out


def test_field_checklists_cover_production_tactile_success_and_fallback() -> None:
    for relative in (
        "docs/testing/phone_field_test_master_checklist_20260710.md",
        "docs/testing/android_stationary_and_field_test_checklist_20260710.md",
    ):
        checklist = (ROOT / relative).read_text(encoding="utf-8")
        assert "TACTILE_LOCAL:stable_aligned_tactile" in checklist
        assert "local tactile steering은 현재 비활성" not in checklist
        assert "항상 TMAP fallback" not in checklist


def test_field_checklists_install_and_reuse_a_frozen_debug_apk() -> None:
    for relative in (
        "docs/testing/phone_field_test_master_checklist_20260710.md",
        "docs/testing/android_stationary_and_field_test_checklist_20260710.md",
    ):
        checklist = (ROOT / relative).read_text(encoding="utf-8")
        assert 'FIELD_APK_DIR="artifacts/android-field-apks/${FIELD_SOURCE_COMMIT}"' in checklist
        assert 'install -m 0400 -T -- apps/android/app/build/outputs/apk/debug/app-debug.apk' in checklist
        assert '--apk "${FIELD_APK}"' in checklist
        assert 'sha256sum -- "${FIELD_APK}"' in checklist

    master = (
        ROOT / "docs/testing/phone_field_test_master_checklist_20260710.md"
    ).read_text(encoding="utf-8")
    assert "sha256sum apps/android/app/build/outputs/apk/debug/app-debug.apk" not in master
