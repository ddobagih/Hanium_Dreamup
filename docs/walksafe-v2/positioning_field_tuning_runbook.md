# WalkSafe Android 위치 현장 튜닝 실행서

이 절차는 Android debug recorder가 내보낸 `walksafe.positioning_trace.v2` 세션 JSONL을 검사하고, train 세션에서
허용 후보를 선택한 다음 독립 holdout에서 비스냅 `filtered_position` 정확도를 판정한다.
`matched_position`은 절대오차 계산에 사용하지 않는다.

## 1. 고정 조건

- 시험 기기: 현재 연결된 `SM-S931N`
- 장착: 가슴 중앙, 세로 방향, 후면이 바깥쪽, 휴대폰 상단이 위쪽
- mount: `PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER`
- 좌표계: WGS84, GeoJSON Point 순서는 `[경도, 위도]`
- 기록 시간축: `elapsed_realtime_ns` (`ANDROID_ELAPSED_REALTIME_NANOS`)
- 측정 시간축: `measurement_elapsed_realtime_ns`; GNSS 및 GNSS 기준에 연결된 센서 표본은 `measurement_utc_epoch_ms`도 포함
- 실측 자료 위치: `local-evidence/positioning/` (Git 제외 대상)

장착 방향, 앱 빌드, 위치 권한 또는 절전 설정을 바꾼 세션은 같은 inventory에 합치지 않는다.
`session_id`와 `route_id`에는 주소나 목적지를 쓰지 않고 UUID만 사용한다.

## 2. 측량 기준점 준비

정확히 측량된 보행 경로와 고정 기준점을 사용한다. 각 기준점에는 전역 고유
`checkpoint_id`, 경로 UUID `route_id`, 1부터 연속인 `ordinal`, WGS84 좌표와
`uncertainty_m`를 기록한다. 측량 원천, 측량 방법, 측량 일시, 장비 또는 기준 도면과 좌표
변환 방법은 attestation 작성 근거로 보관한다.

`uncertainty_m`가 0.5m를 초과하거나 그 이하임을 입증할 수 없는 기준점은 2m 목표 판정에
사용할 수 없다. TMAP 선형, 일반 휴대폰 GNSS 표시값, 눈대중으로 찍은 지도 점은 측량 정답이
아니다.

## 3. 환경과 반복 프로토콜

개방 하늘, 건물 벽, 수목, 평행 보도, 교차로·90도 회전, 도심 협곡을 서로 구분해 기록한다.
각 경로는 같은 장착과 보행 방식으로 최소 6회 반복한다. 정방향과 역방향, 보통·느린 보행,
안내 청취 정지, 캐인 스윕 후 재출발 조건을 metadata와 현장 기록에 남긴다.

각 기준점에서는 기준 위치에 선 뒤 recorder의 stationary 상태가 wire 값 `stationary`인 채로 1.5초
이상 정지하고 그 다음 marker를 누른다. marker보다 미래의 위치 샘플은 사용되지 않으며
양방향 보간도 하지 않는다. 한 세션을 train과 holdout으로 나누거나 복제하지 않는다.

## 4. export 무결성과 개인정보

세션 JSONL은 첫 header, 1부터 연속인 `seq`와 strictly increasing `elapsed_realtime_ns`를 가진
`position_sample`/`checkpoint_mark` 본문, 마지막 footer로 구성된다. footer `record_count`는
본문 수이며, `content_sha256`은 footer
이전 header와 본문 compact JSON line 각각의 UTF-8 bytes와 LF를 순서대로 포함한다. 도구는
footer count와 digest가 다르면 세션 전체를 거부한다.

`source=gnss`는 Android `Location`의 원래 측정 단조시간과 UTC를 보존한다.
`sensor_gnss_anchored`/`checkpoint_gnss_anchored`는 센서 단조시간을 가장 최근 GNSS 기준으로
UTC에 연결했다는 뜻이고, `sensor_monotonic_only`/`checkpoint_monotonic_only`는 연결할 GNSS
기준이 없어서 UTC가 없다는 뜻이다. v1 파일과 header/footer 버전 혼용은 거부하며, 새 v2
세션을 다시 수집해야 한다. 이 단계에서는 PPK 기준 궤적을 입력하거나 계산하지 않는다.

Android SAF로 내보낸 JSONL/GeoJSON은 정밀 위치를 포함하는 평문이다. 메신저, 공개 클라우드,
Git, 이슈 첨부에 올리지 않는다. 접근이 제한된 로컬 저장소에서
`local-evidence/positioning/`으로 옮기고 평가 후 보존 정책에 따라 삭제한다.

## 5. 검사와 결정론적 split

세션 export를 `local-evidence/positioning/traces/`에 한 파일씩 둔다.

```bash
python3 -B scripts/evaluate_android_positioning_offline.py validate \
  --trace local-evidence/positioning/traces \
  --truth local-evidence/positioning/survey-checkpoints.geojson \
  --search-config configs/android_positioning_search_v1.json

python3 -B scripts/evaluate_android_positioning_offline.py split \
  --trace local-evidence/positioning/traces \
  --truth local-evidence/positioning/survey-checkpoints.geojson \
  --seed walksafe-positioning-field-v1 \
  --holdout-fraction 0.25 \
  --output local-evidence/positioning/split.json
```

`validate` 출력의 `trace_sha256`은 정렬된 session id와 각 JSONL 전체 SHA-256을 결합한 inventory
digest다. split에는 알고리즘, seed, fraction, 전체 session hash inventory가 들어간다. 이후
도구는 split을 원본에서 재계산하므로 세션 추가·수정, 사후 holdout 교체, 양쪽 세션 중복을
거부한다.

## 6. train 튜닝

```bash
python3 -B scripts/evaluate_android_positioning_offline.py tune \
  --trace local-evidence/positioning/traces \
  --truth local-evidence/positioning/survey-checkpoints.geojson \
  --split local-evidence/positioning/split.json \
  --search-config configs/android_positioning_search_v1.json \
  --output local-evidence/positioning/tuned.json
```

후보 선택은 train session hash inventory와 search config hash에 결속된다. `evaluate`는 후보와
train metric을 다시 계산해 일치하지 않으면 거부하며 holdout은 튜닝 입력으로 사용하지 않는다.

## 7. 현장 attestation과 holdout 판정

source 문자열 자체 선언만으로는 목표를 달성할 수 없다. 측량 책임자가 validate 출력 hash와
실제 측량 기록을 확인한 뒤 아래 별도 JSON을 작성한다.

```json
{
  "schema_version": "walksafe.positioning_field_attestation.v1",
  "trace_sha256": "validate가 출력한 trace_sha256 64자리",
  "truth_sha256": "validate가 출력한 truth_sha256 64자리",
  "surveyed_source": "측량 기준점 원천 식별자",
  "survey_method": "사용한 측량 방법",
  "maximum_checkpoint_uncertainty_m": 0.5,
  "expected_device_model": "SM-S931N",
  "expected_mount": "PORTRAIT_BACK_OUT_TOP_UP_CHEST_CENTER",
  "operator_confirmed": true
}
```

`maximum_checkpoint_uncertainty_m`는 hash로 봉인된 truth의 실제 최대값과 정확히 같아야 한다.
빈 값, 추정값 또는 형식상 확인만으로 `operator_confirmed`를 true로 두지 않는다.

```bash
python3 -B scripts/evaluate_android_positioning_offline.py evaluate \
  --trace local-evidence/positioning/traces \
  --truth local-evidence/positioning/survey-checkpoints.geojson \
  --split local-evidence/positioning/split.json \
  --search-config configs/android_positioning_search_v1.json \
  --tuned-config local-evidence/positioning/tuned.json \
  --attestation local-evidence/positioning/field_attestation_v1.json \
  --output local-evidence/positioning/holdout-report.json
```

`TARGET_MET`은 attestation의 exact trace/truth hash, 측량 원천·방법·불확도, config와 일치하는
기기 모델·장착, `operator_confirmed=true`, 최소 holdout 세션/marker와 P50/P95/2m
coverage/availability 임계값을 모두 만족할 때만 가능하다. attestation 없음, 합성 truth,
0.5m 초과 불확도, 부족한 holdout은 항상 `INSUFFICIENT_EVIDENCE`다.

도구 계약 테스트는 외부 패키지 없이 실행한다.

```bash
python3 -m unittest discover -s tests -p 'test_android_positioning_offline.py'
```
