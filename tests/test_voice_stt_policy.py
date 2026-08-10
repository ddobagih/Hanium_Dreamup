import sys
import threading
from types import SimpleNamespace

from voice.stt import LocalSTTEngine, summarize_acoustic_evidence


def test_clear_spoken_segments_allow_intent_execution():
    evidence = summarize_acoustic_evidence(
        [
            {"text": "목적지 서울역", "avg_logprob": -0.2, "no_speech_prob": 0.05},
            {"text": "설정해", "avg_logprob": -0.3, "no_speech_prob": 0.1},
        ]
    )

    assert evidence.execution_allowed is True
    assert evidence.confidence > 0
    assert evidence.no_speech_probability == 0.1


def test_no_speech_or_low_logprob_fail_closed():
    likely_silence = summarize_acoustic_evidence(
        [{"text": "신고해", "avg_logprob": -0.2, "no_speech_prob": 0.8}]
    )
    uncertain_words = summarize_acoustic_evidence(
        [{"text": "신고해", "avg_logprob": -1.2, "no_speech_prob": 0.1}]
    )
    missing_diagnostics = summarize_acoustic_evidence([{"text": "신고해"}])

    assert likely_silence.execution_allowed is False
    assert uncertain_words.execution_allowed is False
    assert missing_diagnostics.execution_allowed is False


def test_stt_lazy_model_load_is_locked(monkeypatch, tmp_path):
    load_count = 0

    class FakeWhisperModel:
        def __init__(self, *_args, **_kwargs):
            nonlocal load_count
            load_count += 1

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=FakeWhisperModel))
    monkeypatch.setattr("voice.stt.verify_model_snapshot", lambda *_args: tmp_path)
    engine = LocalSTTEngine(
        model_size="owner/test",
        model_revision="a" * 40,
        model_manifest_path=tmp_path / "manifest.json",
        model_manifest_sha256="b" * 64,
        device="cpu",
        compute_type="int8",
    )
    threads = [threading.Thread(target=engine.load) for _ in range(4)]

    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert load_count == 1
