# PWA 및 백엔드 진행 상태

작성 기준일: 2026-05-13 KST

## 2026-05-17 최신 보정

PWA의 기본 데모 경로는 여전히 fake detector이지만, `NEXT_PUBLIC_DETECTOR_MODE=server`와 backend `MODEL_ARTIFACT_PATH`를 설정한 server detector mode가 추가됐다. 2026-05-15 headless fixture E2E에서는 `/detect` 결과로 `source: "server"`와 `metadata.source: "server"` 신고 저장이 확인됐다.

아직 Android 실폰/목걸이 착용 카메라 field test, PWA 설치/offline/TalkBack, 브라우저/실폰 마이크 E2E는 통과 근거가 없다.

## 현재 결론

PWA와 백엔드는 fake detector로 데모/API 흐름을 확인할 수 있고, v2 `best.pt`가 준비된 개발 환경에서는 server detector mode로 `/detect` 기반 신고 흐름을 smoke test할 수 있다. 실제 안전 판단은 아직 금지이며, `source: "fake"` 데이터는 데모/API 검증용으로만 사용한다.

## PWA

현재 구현 또는 문서화된 기능:

- 모바일 후면 카메라 권한 요청
- 카메라 영상 중심 보행 화면
- fake detector 기반 4개 위험 클래스 순환 생성
- server detector mode에서 backend `/detect` 결과를 `DetectionEvent`로 변환
- 카메라 프레임 위 bbox 오버레이
- GPS 위치 표시
- DeviceOrientation `alpha` 기반 방향 표시
- Web Speech API TTS 경고
- 위험 유형별 진동 패턴
- 음성 꺼짐 상태에서 더 강한 진동 fallback
- 6초 음성 경고 쿨다운
- 현재 위험 신고 버튼
- 신고 전 위치가 있으면 중복 후보 조회
- 중복 후보가 있어도 보행 중 확인 모달로 사용자를 멈추지 않고 advisory로만 표시
- 신고 시 카메라 프레임 JPEG 캡처
- 신고 성공/실패 haptic feedback
- PWA manifest와 service worker 기본 캐시

현재 모델 상태 표시는 `데모 탐지 모드`, `서버 탐지 모드`, `모델 연결 대기`로 분기한다.

## 관리자 화면

`/admin`에서 신고 운영 흐름을 확인할 수 있다.

- 최신 신고 목록 조회
- 최신순, 신뢰도순, 상태순 정렬
- 상태, 위험 유형, 소스, 날짜, 반경 필터
- 신고 상세 조회
- 신고 이미지 미리보기
- 위치 품질 표시: `missing`, `low`, `medium`, `high`
- 검토 플래그 표시: fake source, low confidence, missing location, low location accuracy, missing heading
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
| `GET` | `/detect/health` | 구현, 모델 env 미설정 시 unavailable |
| `POST` | `/detect` | 구현, v2 `.pt` adapter 준비 시 server detection |

업로드 정책:

- 허용 이미지: `image/jpeg`, `image/png`, `image/webp`
- 기본 최대 크기: 8 MB
- 초과 시 `413 upload_too_large`
- 미지원 MIME이면 `400 unsupported_image_type`
- 파일명 확장자와 MIME이 충돌하면 `400 image_extension_mismatch`
- 빈 파일이면 `400 empty_image`
- 파일 헤더가 MIME과 맞지 않으면 `400 image_content_mismatch`

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

## 모델 미설정 상태와 server mode

`/detect/health`는 모델 env가 없거나 산출물을 찾을 수 없으면 아래 상태를 반환한다.

```json
{
  "model_status": "unavailable",
  "model_version": null,
  "reason": "model_not_configured"
}
```

`POST /detect`는 모델 미설정 상태에서 `503 model_unavailable`을 반환한다. fake detector 결과를 서버 모델 결과처럼 반환하지 않는 것이 현재 정책이다.

v2 `best.pt`를 `MODEL_ARTIFACT_PATH`로 설정한 개발 환경에서는 `/detect/health`가 `ready`가 되고, PWA server detector mode가 `source: "server"` 신고를 만들 수 있다. 이 근거는 headless fixture/known-positive smoke이며 실폰 field 성능 근거는 아니다.

## 다음 작업

1. Android 실폰/목걸이 착용 환경에서 fake/server mode를 분리해 카메라, GPS, 방향, TTS, 진동, 신고 흐름을 확인한다.
2. PostGIS 접근 가능한 환경에서 reports 테스트와 HTTP smoke를 재검증한다.
3. 브라우저/실폰 마이크 STT E2E와 TTS HTTP cache/fallback/청취 평가를 확인한다.
4. IMU/DeviceMotion 기반 이동 방향과 GPS 보정 상태를 별도 카드로 분리한다.
5. 실환경 테스트 전 fake 신고 데이터를 성능 집계에서 제외하는 운영 규칙을 확정한다.
