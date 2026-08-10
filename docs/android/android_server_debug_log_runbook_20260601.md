# Android server debug log runbook - 2026-06-01

## 범위

이 runbook은 실기기 화면을 직접 공유하지 않아도 Android native ARCore/TFLite depth 문제를 분석할 수 있도록 **metadata-only debug log**를 local/dev backend에 남기는 절차다.

이 기능은 `/reports/v2` 신고와 분리된다. 공공기관 제출, 운영 DB 저장, image upload, GPS 저장, 외부 공개, secret/API 비용 발생과 무관하다.

## 안전 원칙

- 기본값은 비활성이다.
- local/dev backend에서만 사용한다.
- Android debug build에서만 network permission을 추가한다.
- release manifest에는 `INTERNET` 권한을 넣지 않는다.
- RGB/YUV frame, raw depth image, confidence image, screenshot, audio, STT transcript, GPS, 주소, 전화번호, device unique id, secret은 전송/저장하지 않는다.
- 서버는 allowlist schema 외 field를 422로 거부한다.
- oversized payload는 413으로 거부한다.
- log upload 성공은 overlay/depth/`N보` PASS가 아니라 원인 분석용 evidence일 뿐이다.

## 서버 endpoint

```text
POST /android/debug/depth-logs
GET  /android/debug/depth-logs/recent?limit=20
```

기본 env:

```text
ANDROID_DEBUG_LOG_ENABLED=false
ANDROID_DEBUG_LOG_DIR=backend/android_debug_logs
MAX_ANDROID_DEBUG_LOG_BYTES=65536
```

저장 파일:

```text
backend/android_debug_logs/android_depth_debug.jsonl
```

`backend/android_debug_logs/`는 로컬 debug 산출물이므로 Git 추적 대상이 아니다.

## Android debug upload 기본값

- debug APK에만 `INTERNET` permission과 cleartext local HTTP 허용이 들어간다.
- release build는 `NoopMetadataLogUploader`를 사용한다.
- Android debug build의 기본 endpoint 후보:

```text
http://127.0.0.1:8000/android/debug/depth-logs
```

실기기에서 노트북 local backend로 붙일 때는 USB 연결 후 `adb reverse`를 사용한다.

```bash
adb reverse tcp:8000 tcp:8000
```

## 로컬 실행 절차

### 1. backend debug log 활성화

```bash
cd /home/ddobagi/Code/hanium-dreamup
ANDROID_DEBUG_LOG_ENABLED=true \
ANDROID_DEBUG_LOG_DIR=backend/android_debug_logs \
PYTHONPATH=. \
.venv/bin/python -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000
```

주의: 이 명령은 local/dev server 실행이다. 운영 배포가 아니다.

### 2. USB reverse 설정

```bash
adb reverse tcp:8000 tcp:8000
```

### 3. debug APK 설치

```bash
adb install -r /home/ddobagi/Code/hanium-dreamup/apps/android/app/build/outputs/apk/debug/app-debug.apk
```

기준 APK SHA-256:

```text
1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9
```

### 4. 앱에서 서버 로그 켜기

앱 상단 debug UI에서 다음 버튼을 누른다.

```text
서버 로그 켜기
```

켜진 상태에서는 UI detail에 다음 형태가 표시된다.

```text
server-log=on queued=N
```

### 5. P0 시나리오 수행

`docs/android/android_device_overlay_depth_checklist_20260601.md` 기준으로 수행한다.

- 중앙/좌/우/상/하 bbox 관찰
- 0.5m/1m/2m 거리 후보 관찰
- rotation 관찰
- stale guard 관찰
- sample count/valid ratio/depth source 관찰

### 6. 서버에서 최근 log 확인

```bash
curl 'http://127.0.0.1:8000/android/debug/depth-logs/recent?limit=20'
```

또는 파일 tail:

```bash
tail -20 backend/android_debug_logs/android_depth_debug.jsonl
```

## log schema 요약

root:

```text
schema_version=android.depth_debug.v1
session_id
entries[]
device_model
android_version
app_version_name
```

entry allowlist:

```text
frame_timestamp_ms
detector_frame_timestamp_ms
detector_age_ms
detection_count
detections_used_for_depth
stale_reason
top_detection_class_name
top_detection_confidence
top_detection_bbox
best_depth_class_name
best_depth_source
best_depth_median_m
best_depth_valid_sample_count
best_depth_valid_sample_ratio
best_depth_bbox
preview_width
preview_height
depth_width
depth_height
transform_path
fallback_reason
```

금지 field 예시:

```text
rgb_frame_ref
raw_depth_ref
confidence_image_ref
screenshot_ref
gps
latitude
longitude
address
audio
transcript
base64
file_path
```

## 문제별 판정 가이드

| 증상 | log에서 볼 항목 | 다음 조치 |
|---|---|---|
| bbox는 뜨는데 depth가 이상 | `transform_path`, `fallback_reason`, `best_depth_bbox`, `depth_width/height` | ARCore mapper P1 구현 우선 |
| stale detection이 남음 | `detector_age_ms`, `stale_reason`, `detections_used_for_depth` | stale guard/UI policy 재점검 |
| sample 부족 | `best_depth_valid_sample_count`, `best_depth_valid_sample_ratio` | bbox erosion/sample policy 조정 |
| Raw depth 없음 | `best_depth_source`, sample count | Full fallback/availability 확인 |
| 회전 후 어긋남 | `preview_width/height`, `transform_path` | `IMAGE_PIXELS -> VIEW` mapper 구현 |
| `N보`가 튐 | `best_depth_median_m`, sample count, source | step guidance confidence gate 강화 |

## PASS로 쓰면 안 되는 항목

- server log upload 성공.
- metadata-only log만으로 실제 거리 정확도 PASS 주장.
- `1보`가 log에 찍혔다는 사실.
- `transform=identity` 상태의 log를 ARCore mapper 구현 PASS로 주장.
- static RGB/video dataset 결과를 ARCore 실기기 depth PASS로 확대.
- `/reports/v2` 또는 report DB 저장과 debug log 저장을 혼동.

## 검증 명령

backend:

```bash
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_android_debug_logs.py -q
```

Android:

```bash
cd /home/ddobagi/Code/hanium-dreamup/apps/android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:processReleaseMainManifest --no-daemon
```

manifest 확인:

```bash
grep -n 'INTERNET\|usesCleartextTraffic' app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
grep -n 'INTERNET\|usesCleartextTraffic' app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml || true
```

기대:

- debug manifest: `INTERNET` 있음.
- release manifest: `INTERNET` 없음.
