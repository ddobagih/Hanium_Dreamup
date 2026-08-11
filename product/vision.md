# Product Vision

## 한 줄 컨셉

- WalkSafe Assist는 시각장애인·저시력 보행자가 스마트폰을 착용형 카메라처럼 사용하고, **Android native ARCore 앱**에서 카메라·depth·TFLite 탐지로 보행 위험을 판단해 TTS/진동/신고 운영으로 이어지게 하는 보행 보조 서비스다.
- Web/PWA는 현재 주 사용자 앱이 아니라 demo, admin/ops, backend contract 검증, 과거 호환 경로로 유지한다.

## 문제 / 기회

- 보행 중 화면 확인이 어려운 사용자에게 점자블록 파손, 방치 킥보드/자전거, 공사 장애물, 노면 파임 같은 위험을 짧은 음성·진동으로 전달한다.
- 지도/내비게이션만으로는 보행로의 실시간 물리 상태와 동적 장애물을 충분히 제공하기 어렵다.
- 위험 현장의 위치 품질, 탐지 metadata, 운영자 검토 상태를 신고 데이터로 남겨 보수 우선순위와 공공 제출 준비에 활용한다.
- Android native로 전환한 이유는 Web/PWA에서 억지로 `N보`를 만드는 것이 아니라 ARCore depth와 실기기 카메라 좌표를 직접 검증하기 위해서다.

## 현재 MVP와 장기 비전

| 구분 | 내용 | 상태/주의 |
|---|---|---|
| 현재 MVP | Android native APK에서 ARCore preview/depth, local TFLite unified-primary detector, bbox overlay, depth sampling을 확인 | APK 빌드/설치 가능. unified asset 미배치 시 legacy two-model fallback 사용. bbox-depth 좌표 정합과 `N보` 실측은 아직 Device PASS 아님 |
| Backend/Admin | `/reports/v2`, `/reports/export`, `/admin`으로 신고 저장·검수·CSV/JSON/GeoJSON export | Android report upload는 coordinate/depth gate 이후 연결 |
| Web/PWA | fake/server/fake-v2/server-v2 demo, policy regression, admin/ops 보조 | 주 사용자 앱으로 더 밀지 않음 |
| Voice/STT/TTS | local/PWA prototype과 intent policy 존재 | Android native gate 전 P0 아님 |
| 장기 비전 | 3~5초 이동 궤적 ROI, 길안내, 정밀 측위 신고, MLOps 자동 고도화 | 현재 완료로 쓰지 않음. 승인/데이터/기기/evidence 필요 |

## 대상 사용자

| 사용자 | 상황 | 핵심 니즈 |
|---|---|---|
| 시각장애인·저시력 보행자 | 휴대폰을 목걸이/착용형 카메라처럼 쓰고 화면을 계속 보지 못함 | 짧고 즉시 행동 가능한 TTS/진동, 자동 감지, 음성 조작, 불필요한 알림 최소화 |
| 보행 인프라 운영자/관리자 | 신고 위치·이미지·metadata를 검토하고 상태를 변경함 | 신고 목록/필터/상세, 위치 품질, `new -> reviewed -> resolved`, export |
| 개발/검증 담당자 | fake, backend, Android TFLite, static dataset, Device evidence를 분리해야 함 | APK hash, detector source, threshold, dataset split, PASS 등급을 명확히 기록 |

## 내가 원하는 최종 모습

- [ ] 사용자는 Android native 앱을 설치하고 카메라/depth/TFLite 기반 보행 보조를 실행한다.
- [ ] bbox overlay와 depth sampling이 같은 객체를 가리킨다는 실기기 evidence가 있다.
- [ ] 실제 거리와 `N보` 안내는 RGB-D/실측 거리 기준으로 검증한다.
- [ ] 손상 점자블록은 조용히 신고 후보로 처리하고, 일반 객체는 접근/경로 차단 위험일 때만 경고한다.
- [ ] 신고 데이터는 위치 품질, 검토 상태, source, model_key, trigger, auto_reported를 운영자가 확인할 수 있다.
- [ ] fake/PWA/headless/model/static/Device evidence를 섞지 않는다.

## 핵심 사용 흐름

1. 보행자가 Android native APK를 설치하고 카메라 권한을 허용한다.
2. ARCore preview와 TFLite detector가 frame을 처리하고, debug overlay가 bbox/depth 상태를 보여준다.
3. depth sampler가 bbox 내부 sample median/confidence를 계산한다.
4. 좌표/depth gate가 통과된 뒤에만 TTS/haptic 경고와 `/reports/v2` upload를 연결한다.
5. 운영자는 `/admin`과 `/reports/export`로 신고를 검수하고 제출 준비 파일을 만든다.
6. 모델/제품 검증자는 Android Device, static image, backend API, PWA demo, voice prototype 결과를 서로 다른 근거로 기록한다.

## 하지 않을 것 / 당분간 보류

- bbox/depth 정합 전 TTS/haptic/report upload를 제품 기능으로 붙이지 않는다.
- static RGB dataset 결과를 ARCore depth/`N보` 정확도 근거로 확대하지 않는다.
- PWA fake/server demo를 실제 보행 안전 근거로 쓰지 않는다.
- Cloud STT/TTS, 유료 API, 배포, 외부 공개, 공공기관 자동 제출은 승인 전 실행하지 않는다.
- 목적지 검색/geocoding, TMAP/Kakao 고도화, MLOps/월 1회 자동 재학습은 후순위다.

## 근거 문서

| 근거 파일 | 반영한 내용 |
|---|---|
| `README.md` | 2026-07-01 현재 Android native 주경로와 APK 기준 |
| `docs/current_status.md` | 현재 구현/검증/남은 gate |
| `apps/android/README.md` | Android APK 빌드/설치/TFLite config/overlay |
| `docs/android/arcore_depth_estimation_architecture.md` | ARCore depth/TFLite/coordinate risk |
| `docs/android/android_device_overlay_depth_checklist_20260601.md` | 실기기 overlay/depth gate 기록 양식 |
| `docs/report_operations.md` | 신고 운영/검수/export 정책 |
| `docs/walksafe-v2/README.md` | v2 policy/backend/PWA/voice 문서 인덱스 |
