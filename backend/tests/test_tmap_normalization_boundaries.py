"""The extracted normalizers share the adapter's error identities and schemas."""

import pickle
import subprocess
import sys

import pytest

from backend.app.services import tmap_pedestrian as adapter
from backend.app.services import tmap_poi_normalization as poi
from backend.app.services import tmap_walking_normalization as walking
from backend.app.services.tmap_response_normalization import (
    TmapPedestrianRouteError,
    TmapPoiSearchError,
)


@pytest.mark.parametrize("error", [TmapPedestrianRouteError, TmapPoiSearchError])
def test_normalizer_error_type_keeps_adapter_identity_and_pickle_import(error):
    assert getattr(adapter, error.__name__) is error
    assert pickle.loads(pickle.dumps(error)) is error


def test_direct_normalization_errors_are_caught_through_existing_adapter_imports():
    with pytest.raises(adapter.TmapPedestrianRouteError) as route_error:
        walking._normalize_tmap_response(None, None)
    assert route_error.value.code == "invalid_tmap_response"
    assert route_error.value.http_status == 502
    assert route_error.value.provider_result_code is None
    with pytest.raises(adapter.TmapPoiSearchError) as poi_error:
        poi._normalize_poi_search_response(None, "fixture")
    assert poi_error.value.code == "invalid_tmap_response"
    assert poi_error.value.http_status == 502
    assert poi_error.value.provider_status is None


def test_normalizers_import_without_transport_runtime_or_application_settings():
    # A fresh interpreter proves import order cannot conceal an upward dependency.
    script = """
import importlib.abc
import sys

class RejectTransportImports(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname in {
            'backend.app.config', 'backend.app.services.tmap_pedestrian',
            'backend.app.services.tmap_runtime', 'httpx',
        }:
            raise AssertionError(f'normalizer imported transport: {fullname}')

sys.meta_path.insert(0, RejectTransportImports())
from backend.app.services.tmap_walking_normalization import _normalize_tmap_response
from backend.app.services.tmap_poi_normalization import _normalize_poi_search_response
result = _normalize_poi_search_response({'searchPoiInfo': {'totalCount': '0'}}, 'fixture')
assert result.results == []
assert result.total_count == 0
"""
    result = subprocess.run(
        [sys.executable, "-B", "-c", script],
        check=False, capture_output=True, text=True, timeout=15,
    )
    assert result.returncode == 0, result.stderr
