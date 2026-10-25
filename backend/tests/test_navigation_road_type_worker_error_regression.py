"""S07-12: provided road-code errors must survive the real worker envelope/decode."""

from hashlib import sha256
import json
from pathlib import Path
from time import monotonic

import pytest

from backend.app.schemas import WalkingRouteResponse
import backend.app.services.tmap_pedestrian as service
from backend.app.services.tmap_runtime import TmapRuntimeError, _decode_response, _process_response_job


CAPTURE = Path(__file__).resolve().parent / "fixtures/road_type_worker"
FIXED_MESSAGE = "TMAP pedestrian route response violates the WalkSafe schema."
ROAD_MESSAGE = "TMAP pedestrian route roadType is invalid."


def request_arguments():
    raw = (CAPTURE / "request.json").read_bytes()
    assert sha256(raw).hexdigest() == "1d36ebf556e3d349ef84ab57dc95816b5eae33c24fc9a86a00916cdd690b2fb9"
    return {"request": json.loads(raw)}


def raw_null_body():
    raw = (CAPTURE / "raw-provider-null.provider-input.json").read_bytes()
    assert sha256(raw).hexdigest() == "2908e74b63a57630d7026ddffc5e76a4f877fe2675f7c857c75a77fb8c4b7c91"
    return raw


def job(body, *, deadline=None):
    return _process_response_job("route", 200, body, request_arguments(),
                                 monotonic() + 10.0 if deadline is None else deadline)


def assert_input_error_survives_decode(body):
    envelope = job(body)
    assert envelope["kind"] == "domain_error"
    assert (envelope["code"], envelope["http_status"]) == ("invalid_tmap_response", 502)
    assert envelope["provider_status"] is None
    assert envelope["provider_result_code"] is None
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", envelope)
    assert (caught.value.code, caught.value.http_status) == ("invalid_tmap_response", 502)
    assert caught.value.message == FIXED_MESSAGE


def test_actual_raw_null_error_survives_production_job_and_decode():
    assert_input_error_survives_decode(raw_null_body())


@pytest.mark.parametrize("bad", [True, -1, 16.5, 2_147_483_648, "bad", "16.5", {}, float("inf")])
def test_other_existing_invalid_present_road_codes_keep_the_same_input_error(bad):
    payload = json.loads(raw_null_body())
    payload["features"][1]["properties"]["roadType"] = bad
    assert_input_error_survives_decode(json.dumps(payload).encode())


@pytest.mark.parametrize("case", ["road-16", "road-999", "road-0", "road-int-max", "provider-absent", "explicit-dto-null"])
def test_existing_six_valid_serialized_outputs_remain_exact_after_worker_decode(case):
    manifest_bytes = (CAPTURE / "manifest.json").read_bytes()
    assert sha256(manifest_bytes).hexdigest() == "b32b0304c04c62b8428bad18454cd260408481a6e3e225c37e405268f5a91f3f"
    records = json.loads(manifest_bytes)["responses"]
    record = next(item for item in records if item["case"] == case)
    provider_record = next(item for item in records if item["case"] == "provider-absent") if case == "explicit-dto-null" else record
    body = (CAPTURE / Path(provider_record["input"]["path"]).name).read_bytes()
    assert sha256(body).hexdigest() == provider_record["input"]["sha256"]
    envelope = job(body)
    assert envelope["kind"] == "success"
    response = _decode_response("route", envelope)
    if case == "explicit-dto-null":
        dto_input = (CAPTURE / Path(record["input"]["path"]).name).read_bytes()
        assert response.model_dump_json().encode() == dto_input
        assert record["provider_raw_null_normalized"] is False
        response = WalkingRouteResponse.model_validate(json.loads(dto_input))
    actual = response.model_dump_json().encode()
    expected = (CAPTURE / Path(record["output"]["path"]).name).read_bytes()
    assert sha256(expected).hexdigest() == record["output"]["sha256"]
    assert actual == expected
    assert response.steps[record["step_index"]].road_type == record["expected_road_type"]


def test_expired_deadline_remains_a_timeout_not_a_road_input_error():
    envelope = job(raw_null_body(), deadline=monotonic() - 1.0)
    assert (envelope["code"], envelope["http_status"]) == ("tmap_timeout", 504)
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", envelope)
    assert (caught.value.code, caught.value.http_status) == ("tmap_timeout", 504)


def test_existing_invalid_json_rejection_keeps_its_fixed_public_error():
    envelope = job(b'{"features":')
    assert (envelope["code"], envelope["http_status"]) == ("invalid_tmap_response", 502)
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", envelope)
    assert (caught.value.code, caught.value.http_status) == ("invalid_tmap_response", 502)
    assert caught.value.message == "TMAP pedestrian route response is not valid JSON."


@pytest.mark.parametrize(("facility", "code", "status", "decoded_code", "decoded_status"), [
    (17, "route_stairs_present", 422, "route_stairs_present", 422),
    # Existing unsupported legacy worker message; this exact roadType fix must not widen it.
    (999, "route_safety_code_unknown", 502, "tmap_processing_unavailable", 503),
])
def test_existing_facility_rejection_and_worker_classification_are_unchanged(facility, code, status, decoded_code, decoded_status):
    payload = json.loads((CAPTURE / "road-16.provider-input.json").read_bytes())
    payload["features"][1]["properties"]["facilityType"] = facility
    envelope = job(json.dumps(payload).encode())
    assert (envelope["code"], envelope["http_status"]) == (code, status)
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", envelope)
    assert (caught.value.code, caught.value.http_status) == (decoded_code, decoded_status)


@pytest.mark.parametrize(("provider_status", "result_code"), [(None, None), (418, 73)])
def test_exact_fixed_public_conversion_preserves_provider_attributes(monkeypatch, provider_status, result_code):
    original = service.TmapPedestrianRouteError(code="invalid_tmap_response", message=ROAD_MESSAGE,
        http_status=502, provider_status=provider_status, provider_result_code=result_code)
    def raise_original(*args, **kwargs):
        raise original
    monkeypatch.setattr(service, "_normalize_tmap_response", raise_original)
    envelope = job(raw_null_body())
    assert (envelope["code"], envelope["http_status"], envelope["message"]) == ("invalid_tmap_response", 502, FIXED_MESSAGE)
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", envelope)
    assert (caught.value.provider_status, caught.value.provider_result_code) == (provider_status, result_code)


@pytest.mark.parametrize(("code", "status", "message"), [
    ("unrelated_error", 502, ROAD_MESSAGE),
    ("invalid_tmap_response", 503, ROAD_MESSAGE),
    ("invalid_tmap_response", 502, ROAD_MESSAGE + " private provider detail"),
])
def test_other_normalizer_errors_are_rethrown_unchanged(monkeypatch, code, status, message):
    original = service.TmapPedestrianRouteError(code=code, http_status=status, message=message,
                                              provider_status=418, provider_result_code=73)
    def raise_original(*args, **kwargs):
        raise original
    monkeypatch.setattr(service, "_normalize_tmap_response", raise_original)
    with pytest.raises(service.TmapPedestrianRouteError) as caught:
        service._process_tmap_route_response(200, raw_null_body(), request_arguments(), deadline_at=monotonic() + 10.0)
    assert caught.value is original


def test_worker_decoder_still_rejects_arbitrary_provider_detail():
    envelope = {"kind": "domain_error", "code": "invalid_tmap_response", "http_status": 502,
                "message": ROAD_MESSAGE + " private provider detail", "provider_status": None,
                "provider_result_code": None}
    with pytest.raises(TmapRuntimeError) as caught:
        _decode_response("route", envelope)
    assert (caught.value.code, caught.value.http_status) == ("tmap_processing_unavailable", 503)
