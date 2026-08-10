# PWA 및 백엔드 진행 상태

작성 기준일: 2026-06-02 KST, 2026-07-10 코드 상태 보정

## 2026-07-11 field addendum

- epoch270 13-class PT를 `real`, img768, unified 단일 model, fallback null로 고정한 field profile을 production PWA에 연결했다.
- Cloudflare quick tunnel의 same-origin `/api` proxy 뒤에서 detect, TMAP, voice, report를 제공하며 field/admin HttpOnly session과 login rate limit을 둔다. Next BFF의 actor ID는 30초 HMAC assertion으로 backend token/role에 결합되고 backend는 서명 없는 actor header를 거부한다.
- `/ready`는 DB·Alembic head, upload root fsync 쓰기, real detector artifact/config binding·실제 blank-frame model load/inference·class order warm-up, TMAP 보행 provider 고정·서버 키 존재를 함께 확인하고 실패 시 503을 반환한다.
- 합성 모바일 browser의 과거 person STOP 기록은 현행 bbox-only STOP 근거가 아니다. damaged 3-frame 자동 신고와 PostGIS 저장은 확인했지만, 당시 backend 119-test 실행은 별도 test DB를 강제하지 않아 field DB를 오염시켰으므로 clean regression 근거에서 철회한다.
- 관리자 grid heatmap UI와 redacted aggregate GeoJSON이 연결됐다.
- 아래 2026-07-10 내용의 “real model 연결 필요”는 해결됐다. 실제 외출용 폰, 안정 domain, 정식 account/RBAC와 운영 backup/retention은 여전히 남는다.

## 2026-07-10 보정

- 주 사용자 앱 경로는 Web/PWA다. `server-v2`가 실제 backend 연동 경로이고 `fake`/`fake-v2`는 개발·계약 검증 전용이다.
- Android native는 ARCore/depth/TFLite 실험·검증 보조 경로이며 Web/PWA 완료 evidence를 대체하지 않는다.
- Backend/Admin은 신고 저장, 관리자 검수·필터, CSV 다운로드와 수동 외부 신고의 운영 기준이다. 과거 로컬 PostGIS 112개 테스트 결과는 운영 DB와 격리되지 않아 회귀 근거에서 철회했다. 2026-07-13에는 Unit Python 238, 격리 Functional Python/PostGIS 241·0 skip, Integration Python 109·0 skip, backend full 316과 canonical PT warm-up이 통과했고, 실외 실폰 E2E와 운영 IAM·audit가 남았다.
- backend 목적지 검색/보행 경로는 Web/PWA navigation hook과 Android 검증 UI에서 사용하지만 outdoor/voice field PASS는 없다.

## 현재 결론

PWA와 백엔드는 v1 fake/server 흐름과 v2 fake/server-v2 흐름을 분리한다. 주 사용자 경로는 실제 backend를 명시한 server mode이며, `source: "fake"` 또는 PWA fake 데이터는 개발·API 검증용으로만 사용한다. Web/PWA의 실제 안전 판단은 브라우저·실폰·Release evidence가 필요하고 Android Device evidence와 별도로 관리한다.

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
- 현재 위험 신고 버튼(v1 개발 경로 전용, v2 화면에서는 비활성)
- v2 tactile damage 자동 신고와 음성 요청 신고 분리
- 음성 요청 신고 완료/실패 TTS 안내
- v1은 신고 전 duplicate lookup을 사용한다. v2는 자동 신고 client cooldown과 server 저장 시 10m·±1분 duplicate 후보 태그를 사용한다.
- 신고 성공/실패 haptic feedback
- PWA manifest와 opt-in service worker의 app shell/static cache

현재 PWA 모델 상태 표시는 `데모 탐지 모드`, `서버 탐지 모드`, `unified-v2 데모 모드`, `unified-v2 서버 모드`, `모델 연결 대기`로 분기한다.

주의: PWA policy/headless 결과는 브라우저·실폰·Release PASS가 아니며 Android ARCore depth/`N보` Device PASS도 아니다. Android evidence 역시 Web/PWA 완료를 대체하지 않는다.

## 관리자 화면

`/admin`에서 신고 운영 흐름을 확인할 수 있다.

- 최신 신고 목록 조회
- 최신순, 신뢰도순, 상태순 정렬
- 상태, 위험 유형, 소스, 날짜, 반경 필터
- v2 `model_key`, `trigger`, `auto_reported` 필터
- CSV export 링크
- JSON/GeoJSON export와 redaction preset
- source/status와 0.001도 고정 격자 Top 5 summary
- `/detect/v2/health` 기반 모델 상태 표시
- 신고 상세 조회
- 신고 이미지 미리보기
- 위치 품질 표시: `missing`, `low`, `medium`, `high`
- 검토 플래그 표시
- 신고 상태 변경: `new`, `reviewed`, `resolved`
- 로딩, 빈 목록, 오류 상태 처리

기관 자동 API 제출은 없다. 관리자는 위 필터로 신고를 검수·누적 관리하고, named 검수된 reviewed·damage·high≤15m·non-fake의 현재 필터 최대 10,000행을 정확 위치 `agency` CSV로 내려받아 외부 기관 채널에 수동 신고한다. 초과 시 413으로 필터 축소를 요구하며 임의 선택·병합 기능은 없다.

## 백엔드

FastAPI/PostGIS 기준 기능:

| method | path | status |
| --- | --- | --- |
| `GET` | `/health` | 구현 |
| `GET` | `/ready` | 구현, DB migration·upload fsync write·detector 실제 warm-up·TMAP provider/key 준비 중 하나라도 실패하면 503 |
| `POST` | `/reports` | 구현 |
| `GET` | `/reports` | 구현, v2 필터 파라미터 포함 |
| `GET` | `/reports/export` | 구현, CSV 기본 및 `format=json`, `format=geojson` 지원 |
| `GET` | `/reports/{report_id}` | 구현 |
| `PATCH` | `/reports/{report_id}/status` | 구현 |
| `GET` | `/reports/duplicate-check` | 구현 |
| `GET` | `/reports/summary` | 구현, source/status와 0.001도 고정 격자 Top 5 요약. 지리 군집 알고리즘은 아님 |
| `POST` | `/reports/v2` | 구현, `unified_walksafe` 또는 legacy `custom_tactile`의 `damaged_tactile_block` 신고만 허용 |
| `GET` | `/uploads/{filename}` | 구현 |
| `GET` | `/detect/health` | 구현, 모델 env 미설정 시 unavailable |
| `POST` | `/detect` | 구현, YOLO `.pt` adapter 준비 시 server detection |
| `GET` | `/detect/v2/health` | 구현 |
| `POST` | `/detect/v2` | 구현, fake 기본 + yolo/real lazy provider |
| `GET` | `/navigation/walking/health` | 구현 |
| `POST` | `/navigation/walking` | 구현, TMAP proxy |
| `GET` | `/navigation/destinations/search` | 구현, TMAP POI search/mock provider |
| `POST/GET` | `/android/debug/*` | 구현, 기본 비활성 local/dev 진단 경로 |

업로드 정책:

- 허용 이미지: `image/jpeg`, `image/png`, `image/webp`
- 기본 최대 크기: 8 MB
- 초과 시 `413 upload_too_large`
- 미지원 MIME이면 `400 unsupported_image_type`
- 파일명 확장자와 MIME이 충돌하면 `400 image_extension_mismatch`
- 빈 파일이면 `400 empty_image`
- 파일 헤더가 MIME과 맞지 않으면 `400 image_content_mismatch`

## 탐지 이벤트 계약

프론트, 백엔드, 향후 모델 추론은 v1 legacy 호환에서는 `_archive_candidates/2026-07-08/docs/inference_contract.md`의 `DetectionEvent` 형식을 참고한다. v2 unified-primary 흐름은 `docs/walksafe-v2/README.md`와 관련 v2 문서를 우선한다. Android native runtime은 `apps/android/README.md`와 `docs/android/arcore_depth_estimation_architecture.md`를 우선한다.

## 모델 미설정 상태와 server mode

`/detect/health`는 모델 env가 없거나 산출물을 찾을 수 없으면 `model_status=unavailable`을 반환한다. `POST /detect`는 모델 미설정 상태에서 `503 model_unavailable`을 반환한다. fake detector 결과를 서버 모델 결과처럼 반환하지 않는 것이 현재 정책이다.

v2 `server-v2` mode는 `/detect/v2/health`와 `/detect/v2` 상태를 별도로 본다. Web/PWA 주 사용자 경로는 이 backend detector를 사용한다. Android 실험·검증 모듈은 local TFLite detector를 별도 runtime으로 사용한다.

## 다음 작업

1. 현재 field profile의 실제 외출용 폰 camera/GPS/mic/TTS/진동과 탐지 latency/error를 검증한다.
2. PWA 설치/offline/TalkBack과 브라우저·실폰 마이크 STT E2E를 주 사용자 경로의 Device/Release evidence로 검증한다.
3. 로컬 PostGIS에서 통과한 report→관리자 검수·필터→CSV 다운로드 흐름을 Web/PWA 실폰과 운영 auth/RBAC·audit 환경에서 검증한다.
4. Android native APK의 bbox/depth 좌표 정합과 report upload는 별도 실험·검증 evidence로 확인한다.
