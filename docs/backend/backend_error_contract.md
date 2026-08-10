# Backend Error Contract

작성 기준일: 2026-07-10

## 목적

프론트엔드가 백엔드 오류를 일관되게 처리할 수 있도록 현재 오류 응답을 정리한다.

## 응답 형태

현재 FastAPI 기본 오류와 직접 정의한 오류가 섞여 있다.

직접 정의한 오류:

```json
{
  "detail": {
    "code": "upload_too_large",
    "max_bytes": 8388608
  }
}
```

문자열 오류:

```json
{
  "detail": "report not found"
}
```

검증 오류:

```json
{
  "detail": [
    {
      "type": "...",
      "loc": ["..."],
      "msg": "...",
      "input": "..."
    }
  ]
}
```

프론트엔드는 `detail.code`가 있으면 우선 사용하고, 없으면 HTTP status와 `detail` 문자열 또는 검증 목록을 fallback으로 처리한다.

## 이미지 업로드 오류

| HTTP | `detail.code` | 프론트엔드 처리 |
| ---: | --- | --- |
| 400 | `unsupported_image_type` | 지원하지 않는 이미지 형식 안내 |
| 400 | `image_extension_mismatch` | 파일 확장자와 이미지 형식 불일치 안내 |
| 400 | `empty_image` | 이미지가 비어 있음 안내 |
| 400 | `image_content_mismatch` | 실제 이미지 파일인지 확인 요청 |
| 413 | `upload_too_large` | 이미지 용량 축소 안내 |

## 모델 오류

| HTTP | `detail.code` | 의미 | 프론트엔드 처리 |
| ---: | --- | --- | --- |
| 503 | `model_unavailable` | 서버 추론 모델 미사용 가능 | fake/클라이언트 탐지 모드 유지 또는 모델 대기 상태 표시 |

현재 `reason` 값:

| `reason` | 의미 |
| --- | --- |
| `model_not_configured` | `MODEL_ARTIFACT_PATH`가 비어 있거나 모델 파일 없음 |
| `model_artifact_unsupported` | v1 adapter가 지원하지 않는 artifact 확장자 |
| `model_class_order_mismatch` | 모델 class order가 v1 API 계약과 다름 |
| `model_dependency_missing` | Ultralytics 또는 Pillow dependency 없음 |
| `model_load_failed` | YOLO weight load 실패 |
| `model_task_unsupported` | detection이 아닌 YOLO task |

`/detect/v2`는 provider를 만들 수 없으면 `503`과 `detail.code=detect_v2_unavailable`을 반환한다. `reason`은 `detect_v2_model_not_configured:...` 또는 `detect_v2_mode_unsupported:...` 형태다. `/detect/v2/health`는 model path와 config만 확인하며 실제 weight load 성공을 보장하지 않는다.

## 신고 API 오류

| HTTP | 조건 | 프론트엔드 처리 |
| ---: | --- | --- |
| 400 | `GET /reports`에서 `lat`, `lng`, `radius_m` 일부만 전달 | 반경 검색 파라미터를 함께 보내도록 수정 |
| 404 | 신고 ID 없음 | 상세 화면에서 삭제/존재하지 않음 표시 |
| 413 | `detail.code=report_metadata_too_large` | metadata JSON 크기 축소 |
| 422 | metadata JSON 형식 오류 또는 필드 검증 실패 | 요청 payload 생성 로직 확인 |

대표적인 422 원인:

- `class_id`와 `class_name` 불일치
- `confidence`가 `0..1` 범위를 벗어남
- `bbox` 좌표가 정규화 범위를 벗어남
- `heading`이 `0` 이상 `360` 미만 범위를 벗어남
- `captured_at`이 datetime 형식이 아님

v2는 다음 조건도 `422`로 거부한다.

- 신고 대상이 `damaged_tactile_block`이 아님
- model key와 `model_class_id` 조합이 허용 계약과 다름
- GPS가 없음

## Android debug API 오류

`/android/debug/*`는 기본 비활성이다.

| HTTP | `detail.code` | 의미 |
|---:|---|---|
| 404 | `android_debug_log_disabled` | local/dev debug 수집 비활성 |
| 413 | `android_debug_log_too_large` | depth log payload 크기 초과 |
| 413 | `android_frame_capture_metadata_too_large` | frame metadata 크기 초과 |
| 422 | `android_frame_capture_metadata_invalid` | frame metadata가 JSON이 아님 |
| 422 | `android_frame_capture_metadata_must_be_object` | frame metadata가 JSON object가 아님 |

## 권장 사용자 메시지

| 상황 | 메시지 방향 |
| --- | --- |
| 네트워크 실패 | 서버 연결 상태를 확인하라는 짧은 안내 |
| `model_unavailable` | 현재 서버 모델 연결 전이라는 상태 표시 |
| 이미지 오류 | 파일 형식, 용량, 실제 이미지 여부 중 해당 원인 표시 |
| 422 | 앱 요청 형식 문제로 신고 전송 실패 표시 |
| 404 | 이미 처리되었거나 없는 신고로 표시 |
