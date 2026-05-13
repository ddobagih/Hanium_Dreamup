# PWA 및 백엔드 진행 상태

작성 기준일: 2026-05-13 KST

## 현재 결론

PWA와 백엔드는 실제 YOLO 모델 없이도 fake detector로 통합 흐름을 확인할 수 있는 상태다. 실제 안전 판단은 아직 금지이며, 모델 연결 전까지 `source: "fake"` 데이터는 데모/API 검증용으로만 사용한다.

## PWA

현재 구현 또는 문서화된 기능:

- 모바일 후면 카메라 권한 요청
- 카메라 영상 중심 보행 화면
- fake detector 기반 4개 위험 클래스 순환 생성
- 카메라 프레임 위 bbox 오버레이
- GPS 위치 표시
- DeviceOrientation `alpha` 기반 방향 표시
- Web Speech API TTS 경고
- 6초 음성 경고 쿨다운
- 현재 위험 신고 버튼
- 신고 시 카메라 프레임 JPEG 캡처
- PWA manifest와 service worker 기본 캐시

현재 모델 상태 표시는 `Fake 탐지` 또는 `모델 연결 대기`로 분기한다.

## 관리자 화면

`/admin`에서 신고 운영 흐름을 확인할 수 있다.

- 최신 신고 목록 조회
- 상태, 위험 유형, 소스, 날짜, 반경 필터
- 신고 상세 조회
- 신고 이미지 미리보기
- 신고 상태 변경: `new`, `reviewed`, `resolved`
- 로딩, 빈 목록, 오류 상태 처리

## 백엔드

FastAPI/PostGIS 기준 기능:

| method | path | status |
| --- | --- | --- |
| `GET` | `/health` | 구현 |
| `POST` | `/reports` | 구현 |
| `GET` | `/reports` | 구현 |
| `GET` | `/reports/{report_id}` | 구현 |
| `PATCH` | `/reports/{report_id}/status` | 구현 |
| `GET` | `/reports/duplicate-check` | 구현 |
| `GET` | `/uploads/{filename}` | 구현 |
| `GET` | `/detect/health` | placeholder |
| `POST` | `/detect` | placeholder |

업로드 정책:

- 허용 이미지: `image/jpeg`, `image/png`, `image/webp`
- 기본 최대 크기: 8 MB
- 초과 시 `413 upload_too_large`
- 미지원 MIME이면 `400 unsupported_image_type`

## 탐지 이벤트 계약

프론트, 백엔드, 향후 모델 추론은 `docs/inference_contract.md`의 `DetectionEvent` 형식을 유지한다.

```json
{
  "class_id": 0,
  "class_name": "damaged_tactile_block",
  "confidence": 0.86,
  "bbox": {"x": 0.18, "y": 0.42, "width": 0.36, "height": 0.24},
  "captured_at": "2026-05-12T12:00:00.000Z",
  "source": "fake",
  "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 12.5},
  "heading": 180.0
}
```

## 실제 모델 미연결 상태

`/detect/health`는 모델 어댑터가 없으면 아래 상태를 반환한다.

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

`POST /detect`는 모델 연결 전 `503 model_unavailable`을 반환한다. fake detector 결과를 서버 모델 결과처럼 반환하지 않는 것이 현재 정책이다.

## 다음 작업

1. YOLO `best.pt`를 ONNX 또는 서버 추론 어댑터로 연결한다.
2. `source` 기본값을 `fake`에서 `onnx` 또는 `server`로 바꿀 조건을 정한다.
3. 모델 연결 후 `Fake 탐지` 문구가 운영 화면에 남지 않는지 확인한다.
4. 로컬 음성 서버 `POST /speech/stt`를 PWA 녹음 UI와 연결한다.
5. IMU/DeviceMotion 기반 이동 방향과 GPS 보정 상태를 별도 카드로 분리한다.
6. 실환경 테스트 전 fake 신고 데이터를 성능 집계에서 제외하는 운영 규칙을 확정한다.
