# Backend Error Contract

작성 기준일: 2026-05-12

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
| `model_adapter_not_implemented` | 모델 파일은 있으나 서버 adapter 미구현 |

## 신고 API 오류

| HTTP | 조건 | 프론트엔드 처리 |
| ---: | --- | --- |
| 400 | `GET /reports`에서 `lat`, `lng`, `radius_m` 일부만 전달 | 반경 검색 파라미터를 함께 보내도록 수정 |
| 404 | 신고 ID 없음 | 상세 화면에서 삭제/존재하지 않음 표시 |
| 422 | metadata JSON 형식 오류 또는 필드 검증 실패 | 요청 payload 생성 로직 확인 |

대표적인 422 원인:

- `class_id`와 `class_name` 불일치
- `confidence`가 `0..1` 범위를 벗어남
- `bbox` 좌표가 정규화 범위를 벗어남
- `heading`이 `0` 이상 `360` 미만 범위를 벗어남
- `captured_at`이 datetime 형식이 아님

## 권장 사용자 메시지

| 상황 | 메시지 방향 |
| --- | --- |
| 네트워크 실패 | 서버 연결 상태를 확인하라는 짧은 안내 |
| `model_unavailable` | 현재 서버 모델 연결 전이라는 상태 표시 |
| 이미지 오류 | 파일 형식, 용량, 실제 이미지 여부 중 해당 원인 표시 |
| 422 | 앱 요청 형식 문제로 신고 전송 실패 표시 |
| 404 | 이미 처리되었거나 없는 신고로 표시 |
