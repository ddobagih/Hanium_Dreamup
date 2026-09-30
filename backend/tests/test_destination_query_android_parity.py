"""07.txt #13: the echoed destination query must equal the Android client's own normalization.

Expected values were produced by the Android ``canonicalDestinationSearchQueryOrNull`` for the
same inputs (BackendWalkingRouteClient.kt), which rejects any response whose ``query`` differs.
"""

import pytest

from backend.app.services.tmap_pedestrian import normalize_destination_query

# (raw utterance, query Android sends, query Android expects back for that sent query)
ANDROID_CASES = [
    ("강남역으로 안내해줘", "강남역", "강남역"),
    ("목적지 강남역 설정해", "강남역", "강남역"),
    ("서울역 가줘", "서울역 가줘", "서울역 가줘"),
    ("서울역까지 가자", "서울역", "서울역"),
    ("목적지로 서울역 설정해", "서울역", "서울역"),
    ("목적지를 서울역 지정해", "서울역", "서울역"),
    ("목적지을 시청역 설정", "시청역", "시청역"),
    ("종로로 안내해줘", "종로", "종로"),
    ("목적지 종로 설정해", "종로", "종로"),
    ("낙성대로", "낙성대로", "낙성대로"),
    ("목적지로 목적지로 서울역 설정해 설정해", "목적지로 서울역 설정해", "서울역"),
]


@pytest.mark.parametrize(("raw", "sent", "expected_echo"), ANDROID_CASES)
def test_backend_normalization_matches_android_for_raw_and_sent_queries(
    raw: str, sent: str, expected_echo: str,
) -> None:
    assert normalize_destination_query(raw) == sent
    assert normalize_destination_query(sent) == expected_echo
