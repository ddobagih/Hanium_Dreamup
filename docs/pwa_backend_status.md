# PWA 및 백엔드 진행 상태

작성 기준일: 2026-06-02 KST

## 2026-06-02 보정

- 주 사용자 앱 경로는 Android native ARCore/TFLite APK다.
- PWA는 demo/API contract 검증, policy regression, admin/ops 보조 경로로 유지한다.
- Backend/Admin은 계속 중요하다. Android native에서 report upload를 붙이면 `/reports/v2`, `/reports/export`, `/admin` 흐름을 재사용한다.
- PWA/STT/TMAP 기능 추가는 Android bbox/depth 정합 gate 이후 후순위로 둔다.

## 현재 결론

PWA와 백엔드는 v1 fake/server 흐름과 v2 fake/server-v2 흐름을 분리해 데모/API 흐름을 확인할 수 있다. 실제 안전 판단은 Android native 실기기 evidence가 필요하며, `source: "fake"` 또는 PWA fake 데이터는 데모/API 검증용으로만 사용한다.

## PWA

현재 구현 또는 문서화된 기능:

- 모바일 후면 카메라 권한 요청
- 카메라 영상 중심 보행 화면
- detector mode: `fake`, `server`, `fake-v2`, `server-v2`
- fake detector 기반 위험 클래스 demo
- server detector mode에서 backend `/detect` 결과를 `DetectionEvent`로 변환
- v2 fake/server-v2 mode에서 unified-v2 detection 표시
- 카메라 프레임 위 bbox overlay
- GPS 위치 표시
- DeviceOrientation `alpha` 기반 방향 표시
- Web Speech API TTS 경고
- 위험 유형별 진동 패턴
- 현재 위험 신고 버튼
- v2 tactile damage 자동 신고와 음성 요청 신고 분리
- 음성 요청 신고 완료/실패 TTS 안내
- 신고 전 위치가 있으면 중복 후보 조회
- 신고 성공/실패 haptic feedback
- PWA manifest와 service worker 기본 캐시

현재 PWA 모델 상태 표시는 `데모 탐지 모드`, `서버 탐지 모드`, `unified-v2 데모 모드`, `unified-v2 서버 모드`, `모델 연결 대기`로 분기한다.

주의: PWA bbox/voice/headless 결과는 Android ARCore depth/`N보`/Device PASS가 아니다.

## 관리자 화면

`/admin`에서 신고 운영 흐름을 확인할 수 있다.

- 최신 신고 목록 조회
- 최신순, 신뢰도순, 상태순 정렬
- 상태, 위험 유형, 소스, 날짜, 반경 필터
- v2 `model_key`, `trigger`, `auto_reported` 필터
- CSV export 링크
- `/detect/v2/health` 기반 모델 상태 표시
- 신고 상세 조회
- 신고 이미지 미리보기
- 위치 품질 표시: `missing`, `low`, `medium`, `high`
- 검토 플래그 표시
- 신고 상태 변경: `new`, `reviewed`, `resolved`
- 로딩, 빈 목록, 오류 상태 처리

## 백엔드

FastAPI/PostGIS 기준 기능:

| method | path | status |
| --- | --- | --- |
| `GET` | `/health` | 구현 |
| `POST` | `/reports` | 구현 |
| `GET` | `/reports` | 구현, v2 필터 파라미터 포함 |
| `GET` | `/reports/export` | 구현, CSV 기본 및 `format=json`, `format=geojson` 지원 |
| `GET` | `/reports/{report_id}` | 구현 |
| `PATCH` | `/reports/{report_id}/status` | 구현 |
| `GET` | `/reports/duplicate-check` | 구현 |
| `POST` | `/reports/v2` | 구현, `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block` 신고만 허용 |
| `GET` | `/uploads/{filename}` | 구현 |
| `GET` | `/detect/health` | 구현, 모델 env 미설정 시 unavailable |
| `POST` | `/detect` | 구현, YOLO `.pt` adapter 준비 시 server detection |
| `GET` | `/detect/v2/health` | 구현 |
| `POST` | `/detect/v2` | 구현, fake 기본 + yolo/real lazy provider |
| `GET` | `/navigation/walking/health` | 구현 |
| `POST` | `/navigation/walking` | 구현, TMAP proxy |

업로드 정책:

- 허용 이미지: `image/jpeg`, `image/png`, `image/webp`
- 기본 최대 크기: 8 MB
- 초과 시 `413 upload_too_large`
- 미지원 MIME이면 `400 unsupported_image_type`
- 파일명 확장자와 MIME이 충돌하면 `400 image_extension_mismatch`
- 빈 파일이면 `400 empty_image`
- 파일 헤더가 MIME과 맞지 않으면 `400 image_content_mismatch`

## 탐지 이벤트 계약

프론트, 백엔드, 향후 모델 추론은 v1은 `docs/inference_contract.md`의 `DetectionEvent` 형식을 유지한다. v2 unified-primary 흐름은 `docs/walksafe-v2/README.md`와 관련 v2 문서를 우선한다. Android native runtime은 `apps/android/README.md`와 `docs/android/arcore_depth_estimation_architecture.md`를 우선한다.

## 모델 미설정 상태와 server mode

`/detect/health`는 모델 env가 없거나 산출물을 찾을 수 없으면 `model_status=unavailable`을 반환한다. `POST /detect`는 모델 미설정 상태에서 `503 model_unavailable`을 반환한다. fake detector 결과를 서버 모델 결과처럼 반환하지 않는 것이 현재 정책이다.

v2 `server-v2` mode는 `/detect/v2/health`와 `/detect/v2` 상태를 별도로 본다. v2 lazy YOLO provider는 연결되어 있지만, Android native 주경로에서는 local TFLite detector가 primary runtime이다.

## 다음 작업

1. Android native APK에서 bbox/depth 좌표 정합을 먼저 확인한다.
2. Android report upload가 필요해지면 `/reports/v2` payload source/metadata allowlist를 정한다.
3. disposable DB에서 Android source가 포함된 report→admin→CSV/JSON/GeoJSON export trace를 검증한다.
4. PWA 설치/offline/TalkBack, 브라우저/실폰 마이크 STT E2E는 후순위 Device evidence로 분리한다.
