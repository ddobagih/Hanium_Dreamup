# Backend API Reference

작성 기준일: 2026-05-12

## 범위

이 문서는 모델 미연결 상태에서 프론트엔드와 백엔드가 공유해야 하는 FastAPI 계약만 정리한다. 탐지 이벤트 필드의 상세 규칙은 `docs/inference_contract.md`를 기준으로 한다.

기본 서버 주소:

```text
http://127.0.0.1:8000
```

## 공통 규칙

- 응답은 JSON이다.
- 신고 생성과 서버 추론 요청은 `multipart/form-data`를 사용한다.
- 날짜/시간은 ISO 8601 문자열을 사용한다.
- 위도/경도는 WGS84 좌표를 사용한다.
- 이미지 원본 파일명은 저장 파일명으로 사용하지 않는다. 서버는 UUID 기반 파일명으로 저장한다.

## 이미지 업로드 정책

| 항목 | 현재 값 |
| --- | --- |
| 허용 MIME | `image/jpeg`, `image/png`, `image/webp` |
| 기본 최대 크기 | `8388608` bytes |
| 저장 위치 | `UPLOAD_DIR` |
| 저장 파일명 | `{report_id}.{jpg|png|webp}` |

검증 순서:

1. multipart `Content-Type`이 허용 목록에 있는지 확인한다.
2. 파일명 확장자가 있으면 MIME과 충돌하지 않는지 확인한다.
3. 최대 크기를 넘지 않는지 확인한다.
4. 파일 바이트의 최소 헤더가 MIME과 맞는지 확인한다.

오류 코드:

| HTTP | `detail.code` | 의미 |
| ---: | --- | --- |
| 400 | `unsupported_image_type` | 허용되지 않은 MIME |
| 400 | `image_extension_mismatch` | 파일명 확장자와 MIME 불일치 |
| 400 | `empty_image` | 빈 파일 |
| 400 | `image_content_mismatch` | 이미지 헤더와 MIME 불일치 |
| 413 | `upload_too_large` | 최대 업로드 크기 초과 |

## GET /health

백엔드 프로세스 상태 확인.

응답:

```json
{
  "status": "ok"
}
```

## GET /detect/health

서버 추론 모델 상태 확인. 현재는 실제 모델 어댑터가 없으므로 unavailable이 정상이다.

응답 예:

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

## POST /detect

서버 추론 자리 표시자 API. 모델 연결 전에는 이미지를 검증한 뒤 `503 model_unavailable`을 반환한다.

요청:

| part | 내용 |
| --- | --- |
| `context` | `DetectContext` JSON 문자열 |
| `image` | 카메라 프레임 이미지 |

`context` 예:

```json
{
  "captured_at": "2026-05-12T12:00:00Z",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 180
}
```

현재 오류 응답:

```json
{
  "detail": {
    "code": "model_unavailable",
    "reason": "model_not_configured",
    "message": "Server inference is not available until a model adapter is implemented."
  }
}
```

## POST /reports

탐지 이벤트와 이미지를 신고로 저장한다.

요청:

| part | 내용 |
| --- | --- |
| `metadata` | `DetectionEvent` JSON 문자열 |
| `image` | 신고 이미지 |

`metadata` 예:

```json
{
  "class_id": 0,
  "class_name": "damaged_tactile_block",
  "confidence": 0.91,
  "bbox": {
    "x": 0.2,
    "y": 0.35,
    "width": 0.4,
    "height": 0.22
  },
  "captured_at": "2026-05-12T12:00:00Z",
  "source": "fake",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 181
}
```

응답은 `ReportResponse`이며 주요 필드는 다음과 같다.

| 필드 | 의미 |
| --- | --- |
| `id` | 신고 UUID |
| `status` | `new`, `reviewed`, `resolved` |
| `image_path` | 정적 이미지 경로 |
| `location_quality` | `missing`, `low`, `medium`, `high` |
| `review_flags` | 검토 필요 사유 목록 |
| `duplicate_report_ids` | 저장 직전 발견한 중복 후보 ID |

## GET /reports

신고 목록을 최신순으로 조회한다.

쿼리:

| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `limit` | 아니오 | `1..100`, 기본 `25` |
| `status` | 아니오 | `new`, `reviewed`, `resolved` |
| `class_name` | 아니오 | 4개 탐지 클래스 중 하나 |
| `source` | 아니오 | `fake`, `onnx`, `server` |
| `created_from` | 아니오 | 생성 시각 시작 |
| `created_to` | 아니오 | 생성 시각 끝 |
| `lat` | 반경 검색 시 필요 | 위도 |
| `lng` | 반경 검색 시 필요 | 경도 |
| `radius_m` | 반경 검색 시 필요 | 검색 반경 미터 |

`lat`, `lng`, `radius_m`는 셋을 함께 보내야 한다.

## GET /reports/duplicate-check

중복 신고 후보를 조회한다.

쿼리:

| 이름 | 기본값 | 설명 |
| --- | ---: | --- |
| `class_name` | 필수 | 탐지 클래스 |
| `captured_at` | 필수 | 탐지 시각 |
| `lat` | 필수 | 위도 |
| `lng` | 필수 | 경도 |
| `radius_m` | `25` | 같은 위치로 볼 반경 |
| `minutes` | `10` | `captured_at` 전후 시간 범위 |

응답:

```json
{
  "duplicate_report_ids": ["..."],
  "reports": []
}
```

## GET /reports/{report_id}

신고 상세 조회.

오류:

| HTTP | 의미 |
| ---: | --- |
| 404 | 신고 없음 |

## PATCH /reports/{report_id}/status

신고 상태 변경.

요청:

```json
{
  "status": "reviewed"
}
```

허용 상태:

```text
new, reviewed, resolved
```
