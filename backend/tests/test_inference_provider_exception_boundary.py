"""Provider-raised unavailable errors must not keep inference children alive."""

from __future__ import annotations

import multiprocessing
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from backend.app.schemas import DetectContext, DetectV2Response
from backend.app.services import detect_v2, inference_process


class _ProviderRaisingUnavailable:
    def detect(
        self, image_bytes: bytes, content_type: str, context: DetectContext,
    ) -> DetectV2Response:
        raise detect_v2.DetectV2ProviderUnavailable("provider raised unavailable")


class _ProviderRaisingPreconditionUnavailable:
    def detect(
        self, image_bytes: bytes, content_type: str, context: DetectContext,
    ) -> DetectV2Response:
        raise detect_v2._DetectV2ProviderPreconditionUnavailable("provider raised unavailable")


@pytest.mark.skipif("fork" not in multiprocessing.get_all_start_methods(), reason="requires fork")
def test_provider_raised_public_unavailable_retires_real_child() -> None:
    runner = inference_process.InferenceProcessRunner(
        timeout_seconds=2.0, startup_timeout_seconds=10.0,
    )
    runner._context = multiprocessing.get_context("fork")
    provider_state = detect_v2.DetectV2ProviderState(
        provider=_ProviderRaisingUnavailable(), status="ready",
    )

    with patch.object(detect_v2, "get_detect_v2_provider_state", return_value=provider_state):
        try:
            with patch.object(runner, "_terminate_worker", wraps=runner._terminate_worker) as retire:
                with pytest.raises(RuntimeError) as caught:
                    runner.run_v2(
                        image_bytes=b"synthetic transport input",
                        content_type="image/png",
                        context=DetectContext(),
                        settings=SimpleNamespace(detect_v2_mode="fake"),
                    )

                assert retire.call_count == 1
                assert runner._process is None
                assert runner._connection is None
                assert not runner._usable
                assert str(caught.value) == "DetectV2ProviderUnavailable: provider raised unavailable"
        finally:
            runner.close()


@pytest.mark.skipif("fork" not in multiprocessing.get_all_start_methods(), reason="requires fork")
def test_provider_raised_private_precondition_retires_real_child() -> None:
    runner = inference_process.InferenceProcessRunner(
        timeout_seconds=2.0, startup_timeout_seconds=10.0,
    )
    runner._context = multiprocessing.get_context("fork")
    provider_state = detect_v2.DetectV2ProviderState(
        provider=_ProviderRaisingPreconditionUnavailable(), status="ready",
    )

    with patch.object(detect_v2, "get_detect_v2_provider_state", return_value=provider_state):
        try:
            with patch.object(runner, "_terminate_worker", wraps=runner._terminate_worker) as retire:
                with pytest.raises(RuntimeError) as caught:
                    runner.run_v2(
                        image_bytes=b"synthetic transport input",
                        content_type="image/png",
                        context=DetectContext(),
                        settings=SimpleNamespace(detect_v2_mode="fake"),
                    )

                assert retire.call_count == 1
                assert runner._process is None
                assert runner._connection is None
                assert not runner._usable
                assert str(caught.value) == "DetectV2ProviderUnavailable: provider raised unavailable"
        finally:
            runner.close()
