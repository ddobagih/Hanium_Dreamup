# 프론트엔드 인계 메모: 모델 미연결 상태

작성 기준일: 2026-05-12

## 현재 전제

실제 YOLO/ONNX 모델은 아직 PWA에 연결하지 않는다. 프론트엔드는 `docs/inference_contract.md`의 `DetectionEvent` 형식으로 신고 API를 호출하면 된다.

사용자는 휴대폰을 목걸이 형태로 목에 걸고 보행한다고 가정한다. 따라서 화면은 장시간 주시하는 UI가 아니라, 목걸이형 카메라 입력과 TTS/진동 알림을 보조하는 상태 화면으로 설계한다.

## 신고 전 권장 흐름

1. 카메라 프레임에서 탐지 이벤트를 만든다.
2. `gps`, `heading`, `accuracy_m`이 있으면 `metadata`에 포함한다.
3. 위치가 있으면 `GET /reports/duplicate-check`로 중복 후보를 확인한다.
4. 중복 후보가 있어도 보행 중 확인 조작을 요구하지 않고, TTS/상태 문구로 “비슷한 신고가 있음”만 안내한 뒤 저장한다.
5. `POST /reports`로 `metadata` JSON 문자열과 이미지 파일을 `multipart/form-data`로 보낸다.
6. 응답의 `location_quality`, `review_flags`, `duplicate_report_ids`를 화면 또는 로그에 표시한다.

## 목걸이 착용 UI 기준

- 위험 안내 문구는 한 문장 안에서 끝낸다.
- “확인하세요”처럼 화면을 보게 만드는 문구보다 “발밑 주의”, “우회하세요”, “천천히 이동”처럼 행동을 직접 알려준다.
- 위험 유형별 진동 패턴을 다르게 둔다.
- 음성 꺼짐 상태에서는 진동을 더 길고 강하게 한다.
- 중복 신고 확인은 보행 중 사용자를 멈추게 하지 않는다.
- 자세한 실기기 확인은 `docs/neck_worn_phone_test_checklist.md`를 따른다.

## API 사용

### 서버 추론 상태 확인

```http
GET /detect/health
```

현재 모델 어댑터가 없으면 아래처럼 응답한다.

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

프론트가 `server` 추론 모드를 붙일 때는 이 값을 먼저 확인하고, `unavailable`이면 모델 대기 화면 또는 fake/local fallback으로 분기한다.

### 서버 추론 요청

```http
POST /detect
Content-Type: multipart/form-data

context={"captured_at":"2026-05-12T12:00:00Z","gps":{"latitude":37.5665,"longitude":126.978,"accuracy_m":9.5},"heading":180}
image=<camera frame image>
```

현재는 실제 모델이 없으므로 `503`과 `model_unavailable`을 반환한다. 이 응답은 정상적인 placeholder 상태이며, 프론트는 실패 토스트보다 "모델 연결 대기" 상태로 처리하는 것이 좋다.

### 중복 확인

```http
GET /reports/duplicate-check?class_name=damaged_tactile_block&captured_at=2026-05-12T12:00:00Z&lat=37.5665&lng=126.978&radius_m=25&minutes=10
```

중복 확인은 위치가 있을 때만 호출한다. 위치가 없으면 바로 신고하되, 응답의 `review_flags`에 `missing_location`이 붙는다.

### 신고 생성

```http
POST /reports
Content-Type: multipart/form-data

metadata=<DetectionEvent JSON string>
image=<captured image file>
```

이미지는 `image/jpeg`, `image/png`, `image/webp`만 허용된다. 기본 최대 크기는 8MB이며, 초과 시 `413`과 `upload_too_large`가 반환된다.

응답에서 프론트가 우선 표시할 필드:

| 필드 | 표시 방법 |
| --- | --- |
| `status` | 신고 처리 상태 |
| `location_quality` | 위치 신뢰도 배지 |
| `review_flags` | 주의 문구 또는 운영자 검토 사유 |
| `duplicate_report_ids` | 중복 가능 신고 안내 |
| `image_path` | 신고 이미지 미리보기 |

## 리뷰 플래그 처리 제안

| flag | 프론트 표시 |
| --- | --- |
| `fake_source` | 테스트 탐지 |
| `low_confidence` | 신뢰도 낮음 |
| `missing_location` | 위치 없음 |
| `low_location_accuracy` | 위치 정확도 낮음 |
| `missing_heading` | 방향 정보 없음 |

## 프론트에서 아직 하지 않아도 되는 일

- 모델 정확도 표시
- 실제 위험 판단 로직 고도화
- fake 데이터를 모델 성능 수치에 포함
- 외부 스토리지 업로드
