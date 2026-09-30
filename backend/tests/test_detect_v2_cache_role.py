"""Direct cache identity regressions without loading model weights."""

from types import SimpleNamespace

import pytest

from backend.app.services import detect_v2


@pytest.fixture(autouse=True)
def reset_provider_cache():
    detect_v2._reset_detect_v2_provider_cache()
    yield
    detect_v2._reset_detect_v2_provider_cache()


def _settings(*, custom=None, coco=None, unified=None, runtime=None, environment="development"):
    return SimpleNamespace(
        detect_v2_custom_tactile_model_path=custom,
        detect_v2_coco_model_path=coco,
        detect_v2_unified_model_path=unified,
        detect_v2_runtime_config_path=runtime,
        detect_v2_image_size=768,
        walksafe_environment=environment,
    )


def test_same_file_list_reallocated_to_different_roles_creates_provider(tmp_path):
    first_model = tmp_path / "first.pt"
    second_model = tmp_path / "second.pt"
    runtime = tmp_path / "runtime.json"
    for path in (first_model, second_model, runtime):
        path.write_bytes(b"synthetic artifact")

    legacy = _settings(custom=first_model, coco=second_model, runtime=runtime)
    unified = _settings(coco=first_model, unified=second_model, runtime=runtime)
    legacy_provider = detect_v2._cached_yolo_provider(legacy)
    assert detect_v2._cached_yolo_provider(legacy) is legacy_provider

    unified_provider = detect_v2._cached_yolo_provider(unified)
    assert unified_provider is not legacy_provider
    assert unified_provider.runtime.custom_tactile_model_path is None
    assert unified_provider.runtime.coco_model_path == first_model
    assert unified_provider.runtime.unified_model_path == second_model
    assert unified_provider.runtime.runtime_config_path == runtime


def test_environment_transition_validates_binding_and_reuses_same_environment(tmp_path, monkeypatch):
    model = tmp_path / "unified.pt"
    runtime = tmp_path / "runtime.json"
    model.write_bytes(b"synthetic model")
    runtime.write_bytes(b"synthetic config")
    validation_calls = []

    def validate(runtime_path, **model_paths):
        validation_calls.append((runtime_path, model_paths))

    monkeypatch.setattr("backend.app.config._validate_runtime_artifact_bindings", validate)
    development = _settings(unified=model, runtime=runtime)
    production = _settings(unified=model, runtime=runtime, environment="production")

    development_provider = detect_v2._cached_yolo_provider(development)
    assert validation_calls == []
    production_provider = detect_v2._cached_yolo_provider(production)
    assert production_provider is not development_provider
    assert validation_calls == [(
        runtime,
        {"unified_model_path": model, "custom_model_path": None, "coco_model_path": None},
    )]
    assert detect_v2._cached_yolo_provider(production) is production_provider
    assert len(validation_calls) == 1
