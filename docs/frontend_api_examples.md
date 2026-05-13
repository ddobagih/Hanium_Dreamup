# Frontend API Request Examples

작성 기준일: 2026-05-12

## 목적

외부 프론트엔드 작업자가 백엔드 연동을 빠르게 확인할 수 있도록 최소 요청 예시만 정리한다. 전체 필드 규칙은 `docs/api_reference.md`와 `docs/inference_contract.md`를 따른다.

기본 API 주소:

```text
http://127.0.0.1:8000
```

## Health Check

```bash
curl http://127.0.0.1:8000/health
```

성공 응답:

```json
{
  "status": "ok"
}
```

## Detect Health

```bash
curl http://127.0.0.1:8000/detect/health
```

모델 연결 전 정상 응답:

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

## Create Report

`sample.jpg`는 실제 JPEG 이미지 파일이어야 한다. 서버는 MIME, 확장자, 파일 헤더를 함께 확인한다.

```bash
metadata='{
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
}'

curl -X POST http://127.0.0.1:8000/reports \
  -F "metadata=$metadata" \
  -F "image=@sample.jpg;type=image/jpeg"
```

프론트엔드는 성공 응답에서 다음 필드를 우선 사용한다.

| 필드 | 사용처 |
| --- | --- |
| `id` | 상세 조회, 상태 변경 |
| `status` | 운영 상태 표시 |
| `image_path` | 업로드 이미지 표시 |
| `location_quality` | 위치 신뢰도 표시 |
| `review_flags` | 운영자 검토 사유 표시 |
| `duplicate_report_ids` | 중복 가능성 표시 |

## List Reports

최신 신고 25개:

```bash
curl "http://127.0.0.1:8000/reports"
```

상태/클래스 필터:

```bash
curl "http://127.0.0.1:8000/reports?status=new&class_name=pothole&limit=20"
```

현재 위치 주변 검색:

```bash
curl "http://127.0.0.1:8000/reports?lat=37.5665&lng=126.978&radius_m=100"
```

`lat`, `lng`, `radius_m`는 셋을 함께 보내야 한다.

## Duplicate Check

신고 전 중복 후보를 확인할 때 사용한다.

```bash
curl "http://127.0.0.1:8000/reports/duplicate-check?class_name=pothole&captured_at=2026-05-12T15:03:00Z&lat=37.50002&lng=127.00002&radius_m=25&minutes=10"
```

응답의 `duplicate_report_ids`가 비어 있지 않아도 서버가 신고 생성을 막지는 않는다. 프론트엔드에서는 “비슷한 신고가 있음” 정도로 표시한다.

## Update Report Status

```bash
curl -X PATCH http://127.0.0.1:8000/reports/{report_id}/status \
  -H "Content-Type: application/json" \
  -d '{"status":"reviewed"}'
```

허용 상태:

```text
new
reviewed
resolved
```

## Server Detect Placeholder

모델 연결 전 `/detect`는 업로드 이미지 검증까지만 수행하고 `503`을 반환한다.

```bash
context='{
  "captured_at": "2026-05-12T12:00:00Z",
  "gps": {
    "latitude": 37.5665,
    "longitude": 126.978,
    "accuracy_m": 9.5
  },
  "heading": 180
}'

curl -X POST http://127.0.0.1:8000/detect \
  -F "context=$context" \
  -F "image=@sample.jpg;type=image/jpeg"
```

현재 예상 응답:

```json
{
  "detail": {
    "code": "model_unavailable",
    "reason": "model_not_configured",
    "message": "Server inference is not available until a model adapter is implemented."
  }
}
```
