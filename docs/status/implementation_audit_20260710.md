# WalkSafe 코드 기반 구현 현황 감사 - 2026-07-10

- 기준일: 2026-07-10 KST
- 판정 대상: 현재 로컬 작업트리, 원안 PDF 2개, 자동 테스트, 모델 평가 산출물
- 전체 판정: **PARTIAL**. 코드 연결은 넓지만 원안의 실서비스 완료 조건과 현장 검증은 충족하지 않았다.

> 2026-07-11 후속 구현 주의: 이 문서는 2026-07-10 시점 감사 snapshot이다. 이후
> epoch270 real/img768 field runtime, production PWA와 Cloudflare 임시 HTTPS,
> field/admin session·rate limit, 4초 미래 ROI, 조건부 점자블록 local steering, 관리자
> 격자 heatmap, local model registry/rollback, 1 Hz Web field telemetry가 구현됐다.
> 길안내 provider는 TMAP으로 고정됐고 목적지 변경·취소와 다음 안내 질의,
> `risk > interaction > navigation` 음성 중재가 연결됐다.
> Android에는 epoch270 기반 unified float32 img768 13-class TFLite를 expected
> primary로 적용했고 asset SHA-256 `92b39d3b...`, `[1,768,768,3]`→`[1,300,6]`
> 계약을 고정했다. legacy pair는 unified load·hash·tensor 실패 fallback으로만 남긴다.
> 연결 `SM-G981N` instrumentation에서 unified/legacy asset 계약·load/invoke 2/2과 primary `fallback_used=false`를 통과했지만,
> 대화형 camera/depth/FPS·실외 Device Field와 배포 적격은 여전히 미통과다.
> 전역 목적지 경로는 TMAP이며 Web만 엄격한 gate의 정상 점자블록을 local steering으로
> 사용한다. Android는 global camera pose→route segment projection evidence 부재로 항상
> TMAP으로 닫는다. 본문의 과거 imgsz640·export
> 대기 문구는 snapshot 기록이다.
> 합성 모바일 브라우저의 person STOP과 damaged 3-frame 자동 신고도 통과했다.
> 과거 비격리 PostGIS 테스트 근거와 아래의 “별도 test DB 결과를 다시 만들기” 항목은
> 철회·완료됐다. 현재 clean regression 근거는 전용 `walksafe_test` DB와 임시 upload root를 강제한
> 최신 독립 검증이다. Unit Python 238개, 격리 Functional Python/PostGIS 241개·0 skip,
> Integration Python 109개·0 skip, backend full 316개와 canonical PT warm-up 통과다. 운영 신고 수 불변 확인은 이전 격리 회귀 근거로 유지한다.
> 최신 판정과 남은 실제 폰 검증은 `current_status.md`와
> `../testing/web_remote_field_test_20260711.md`를 우선한다.

## 확정된 제품 범위와 정정

- 주 사용자 앱은 **Web/PWA**다. 2026-05-31의 Android-primary 내부 기록은 2026-07-10 사용자 확인으로 superseded됐다.
- Android native ARCore/TFLite 앱은 depth와 기기 내 추론을 연구·검증하는 보조 경로다. 구현량이 많다는 이유로 주 제품으로 해석하지 않는다.
- 공공기관 API 자동 연계는 제공하지 않는다. 앱 신고를 관리자가 검수·필터링해 CSV로 내려받고, 기관별 외부 채널에 별도로 수동 신고하는 방식이 확정됐다.
- 완전 자동 MLOps는 원안 요구와 달리 현재 구현되지 않았다. 범위 결정으로 숨기지 않고 추가 구현 gap으로 남긴다.

## 판정 기준

| 상태 | 의미 |
|---|---|
| `IMPLEMENTED` | 제품 코드 경로가 연결되어 있고 자동 검증 근거가 있다. |
| `PARTIAL` | 코드 일부가 있으나 핵심 연결, 운영 환경 또는 검증이 남았다. |
| `NOT_IMPLEMENTED` | 원안 기능에 해당하는 제품 코드가 없다. |
| `NOT_VERIFIED` | 코드가 있어도 실기기, DB, 외부 API 또는 운영 환경 검증이 없다. |
| `SCOPE_CHANGED` | 원안과 현재 제품 결정이 다르며 공식 변경 확인이 필요하다. |
| `SCOPE_CONFIRMED` | 원안과 다르지만 프로젝트 책임자가 현재 운영 범위를 명시적으로 확정했다. |

`Static`, `Unit`, `Integration`, `Web Mobile Field`, `Android Device`, `Model Eval`, `Release`는 서로 다른 증거 등급이다. 낮은 등급의 PASS를 높은 등급 완료로 해석하지 않는다.

## 원안 요구사항 대조

| ID | 원안 요구 | 현재 코드 상태 | 구현 판정 | 검증 판정 | 남은 일 또는 결정 |
|---|---|---|---|---|---|
| R01 | 통합 PWA와 실서비스 도메인 | Next.js Web/PWA가 카메라·탐지·위험 피드백·음성·길안내·신고를 조립하고 field profile에서 PWA를 활성화한다. Cloudflare 임시 HTTPS와 Chromium lifecycle 근거는 있으나 안정 운영 도메인은 없다. | `PARTIAL` | Static/정책/임시 HTTPS PASS, Release 미검증 | 고정 HTTPS/domain 배포와 모바일 브라우저 설치·update E2E가 필요하다. |
| R02 | YOLO 기반 실시간 장애물 감지 | Web `server-v2`는 browser camera의 새 media frame을 기본 450ms 간격으로 backend v2 detector에 보내는 sampled 탐지다. 정지/중복 frame을 거부하고 응답 stale·실패·camera 정지를 “위험 없음”이 아닌 탐지 불가로 표시하며, 경고·자동 신고 후보는 서로 다른 3 frame과 700ms를 모두 요구한다. field profile은 SHA 고정 unified 13-class PT를 real/img768/fallback 없음으로 사용한다. Android 보조 경로에는 TFLite detector가 있다. | `PARTIAL` | Unit/합성 real E2E PASS, 실외 미검증 | 실제 폰에서 camera→server→경보 FPS/지연, 정지 frame fail-safe와 약한 class의 실외 위험 품질을 검증한다. Android unified TFLite export는 별도 보조 경로다. |
| R03 | IMU 기반 3~5초 미래 궤적 ROI | Web은 absolute heading, GPS/보폭 속도와 route bearing을 결합한 4초 미래 위치·screen ROI shift를 runtime에 연결했다. 이는 bounded advisory projection이며 정밀 dead reckoning은 아니다. | `PARTIAL` | Unit PASS, mobile field 미검증 | 실폰 센서 편차·보행 경로에서 projection/ROI 정합과 안전성을 검증한다. |
| R04 | 완전 음성 제어 내비게이션 | Web은 순수 executor와 callback spy로 목적지·후보·길안내 dispatch를 검증했다. Android도 신고·목적지 설정/변경·후보 번호 선택·취소·다음 안내·중지를 action에 연결하고 부정문·검색 중·범위 밖 선택을 fail-closed 처리한다. | `PARTIAL` | 로컬 STT/정책·Web dispatch·Android JVM PASS, 실폰 미검증 | 모바일 Web/Android mic/TTS·TalkBack에서 전체 음성 흐름과 접근성을 검증한다. |
| R05 | IMU+GPS Kalman/dead reckoning 정밀 좌표 | Web은 GPS/heading을, Android는 GPS 정확도·점프 필터와 step count를 사용한다. gyroscope/Kalman/dead reckoning 보정은 없다. | `NOT_IMPLEMENTED` | 해당 없음 | 웹 센서 제약을 반영한 정밀 측위 알고리즘과 기준 데이터셋을 구현하거나 GPS 품질 gate 수준으로 요구를 변경한다. |
| R06 | 자동 신고와 PostGIS 적재 | Web v2 신고 hook, gateway-bound Android 보조 client, `/reports/v2`, PostGIS schema/제약, 중복 후보, 관리자 검수/export 코드가 있다. | `PARTIAL` | 전용 test DB 전체 회귀·합성 Web→PostGIS·과거 USB smoke PASS | 기존 운영 오염 후보 승인 정리와 모바일 Web/Android 신고·운영 account E2E가 필요하다. |
| R07 | 지자체 민원 시스템 자동 연계 | 공공기관 API 자동 연계 대신 관리자 검수·필터 기반 CSV 다운로드 후 외부 채널 수동 신고로 운영 범위가 확정됐다. export 코드는 구현돼 있다. | `SCOPE_CONFIRMED` | 로컬 DB 검수·필터 CSV PASS | 관리자 auth/RBAC를 갖춘 운영 환경에서 검수→CSV→수동 신고 절차를 검증한다. 임의 선택 병합 기능은 현재 없다. |
| R08 | GIS 히트맵/군집 분석 | backend는 정확 좌표 원천을 0.001도 고정 격자로 묶은 요약/GeoJSON을 제공하고 Admin은 bounds 기반 heatmap cell UI를 표시한다. 이는 통계 grid이지 지리 군집 알고리즘은 아니다. | `PARTIAL` | PostGIS summary/export와 UI 정책 PASS | 운영 데이터에서 grid 해석·성능을 검증하고 고급 지리 군집이 필요하면 별도로 구현한다. |
| R09 | 현장 데이터 자동 업로드, 재학습, 무중단 배포 | dataset build/train/eval/export 보조 스크립트는 있으나 S3, 자동 재학습, registry, CDN, blue-green 배포는 없다. | `NOT_IMPLEMENTED` | 수동 모델 파이프라인만 검증 | 개인정보 동의, storage, version/rollback을 먼저 정한 뒤 별도 MLOps lane으로 구현한다. |
| R10 | 저신호 오프라인 PWA | service worker shell/static cache와 offline queue helper가 있다. API는 no-store이고 queue helper는 제품 화면에 연결되지 않았다. | `PARTIAL` | Unit/Static만 PASS | 실제 설치/오프라인 field와 queue 제품 연결을 검증한다. |
| R11 | 운영 보안과 실서비스 | production gateway는 field/admin named account, 분리된 service token/session secret, 12시간 HttpOnly HMAC cookie, 역할 격리, 파일 기반 로그인 limiter, actor 상태 이력/export audit를 fail-closed로 요구한다. | `PARTIAL` | 정책/통합 PASS, Release 미검증 | 조직 IdP·credential rotation/lifecycle, stable edge, 실행 중 서비스 재배포와 운영 증거가 필요하다. |
| R12 | 정확도 90%, 경보 1초, 월 1회 재학습, 15% 향상, coverage 60% | metric 정의와 측정 체계가 일부만 있다. 현재 13-class best mAP50은 0.554, mAP50-95는 0.417이며 E2E 경보 지연과 coverage는 측정하지 않았다. | `PARTIAL` | 목표 미충족/미측정 | 지표 정의, 독립 test/field set, latency/coverage 계측을 고정한다. |

## 현재 구현된 기능

### Web/PWA 주 앱

- 카메라, GPS/heading, v1·v2 detector, 위험 피드백, 신고, 음성, 길안내와 PWA 상태를 첫 화면에서 조립한다.
- 음성으로 현재 위치, 목적지 검색·변경·취소, 후보 선택, 길안내 시작·재탐색·중지, 다음 안내 질의와 신고를 처리한다.
- 목적지 검색, 보행 경로 요청, route progress/off-route/자동 재탐색 상태기가 있다.
- 위험 안내가 음성 명령 상호작용보다, 상호작용 안내가 일반 길안내보다 우선한다. 위험 경고는 진행 중 녹음을 취소할 수 있다.
- TMAP 방향과 camera future ROI에 정렬된 정상 점자블록을 근거리 보조로만 사용한다. 점자블록 경로망을 만들거나 TMAP route를 대체하지 않는다.
- `server-v2`는 새 camera media frame을 sampled 전송하며 frozen/stale/unavailable 상태를 “위험 없음”으로 표시하지 않는다. 합성 검증은 실제 폰의 지연·FPS·실외 품질 근거가 아니다.
- v2 신고는 이미지·위치·metadata를 `/reports/v2`로 전송한다.
- 관리자 화면은 상태·클래스·source·model·trigger·날짜·반경·fake/demo 필터, 상태 이력, CSV/JSON/GeoJSON export를 제공한다.

코드 근거: `apps/web/app/page.tsx`, `apps/web/app/_walksafe/`, `apps/web/app/admin/page.tsx`, `apps/web/lib/`.

### Android 보조 연구 경로

- ARCore camera preview, Raw/Full Depth snapshot, 좌표 mapper, bbox/depth overlay가 연결되어 있다.
- JSON runtime config 기반 unified-primary/legacy-fallback TFLite loader가 있다.
- object tracking, stale guard, depth sampling, TTC, route-bearing 보조 위험 정책이 있다.
- Device Gate 이후 TTS/haptic, damage-only report 후보와 multipart upload가 연결되어 있다.
- GPS/step count, 목적지 검색, TMAP 보행 경로 요청, route progress/off-route/reroute 코드가 있다.
- 명시적 음성 신고는 Android `SpeechRecognizer`와 연결되어 있다.
- debug field session은 app-private manifest와 JSONL, 4 MiB 회전·재시작 복구·ADB 회수/요약을 제공하며 정확 좌표·이미지·음성을 저장하지 않는다. 주기 telemetry와 CameraX 표본은 각각 최대 1 Hz이고 event는 발생 시 기록한다. 실제 미지원 기기 gate는 CameraX 표본 60개 이상, 15분 span, 표본 간격 1~30초의 sparse continuity를 확인한다.

코드 근거: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/` 아래 `MainActivity`, `depth`, `inference`, `navigation`, `report`, `feedback`, `device` 패키지.

### Backend

- FastAPI health/upload/detect v1·v2/report/navigation/Android debug router가 있다.
- `/detect/v2`는 기본 `fake`이며, 설정 시 unified PT 우선/legacy pair fallback을 lazy load한다.
- `/reports/v2`의 damage-only/GPS/metadata 정책, 10m·1분 중복 후보, status history, CSV/JSON/GeoJSON export가 있다.
- 목적지 검색과 보행 경로 제품 계약은 TMAP 고정이며 다른 provider 설정·응답은 거부한다.
- `/reports/export`는 필터 전체를 CSV 기본, JSON/GeoJSON 옵션으로 내보내며 CSV formula injection 방어를 적용한다.

코드 근거: `backend/app/api/`, `backend/app/services/`.

### Voice

- faster-whisper local STT, 13개 rule-based intent, Qwen3 local TTS cache prototype API가 있다.
- voice server는 intent를 분류해 반환하며 사용자 동작은 Web callback 또는 Android 플랫폼 코드가 수행한다.
- 주 Web의 사용자 출력은 browser `speechSynthesis`이며 Qwen3 WAV API는 이 피드백 경로에 연결되지 않았다.
- rule score는 acoustic confidence가 아니라 고정 heuristic이므로 실제 STT 신뢰도로 표현하지 않는다.

코드 근거: `voice/server.py`, `voice/stt.py`, `voice/intents.py`, `voice/tts.py`.

### Model/Data

- unified 13-class dataset은 196,506 images, 607,814 boxes 규모로 materialize됐다.
- YOLO26n img768 학습은 300 epoch 완료했고 epoch 270 `best.pt`가 현재 후보이다.
- best validation은 26,416 images/69,467 instances, mAP50 0.554, mAP50-95 0.417이다.
- `damaged_tactile_block`과 `e_scooter_obstruction`은 강하지만 `curb_step` recall 0.195, `uneven_sidewalk` recall 0.076으로 실사용 최종 모델로 확정할 수 없다.
- validation에서 corrupt image/label 2,331장이 제외돼 데이터 품질 정리가 필요하다.
- 2026-07-10 snapshot에서 backend unified runtime은 `imgsz=640`이었다. 2026-07-11 field profile은 img768로 보정됐지만 네트워크 포함 실시간 성능·지연은 별도로 평가해야 한다.

근거: `reports/walksafe_best_eval_20260708/final_evaluation_report.md`, `reports/walksafe_best_eval_20260708/training_reference_index_20260708.md`.

## 현재 검증 결과

| 영역 | 2026-07-10 재검증 | 의미 |
|---|---|---|
| Backend | historical `112 passed, skipped 0` | 별도 test DB를 강제하지 않은 실행으로 뒤늦게 확인돼 clean regression 근거가 아니다. |
| Voice | `81 passed`; 실제 샘플 `신고해.m4a` -> `create_report` | local STT/intent 수준이며 모바일 Web mic/TTS 완료가 아니다. |
| Root Python tests | `88 passed`, Pillow deprecation warning 8건 | Voice 77개, offline model/depth 5개, field log summary 6개를 합친 수치다. Field/Release 근거는 아니다. |
| 통합 Python 실행 | historical `200 passed, skipped 0`, warning 8건 | backend 부분이 non-isolated DB를 사용했으므로 현재 release 근거로 인용하지 않는다. |
| Web | lint/typecheck 및 PWA/risk/report/navigation/settings/admin 정책 스크립트 PASS | 정적/policy 수준이며 camera/GPS/SW/browser E2E가 아니다. |
| Android | `108 tests`, failures/errors/skipped 0, BUILD SUCCESSFUL | 현장 로그와 ScrollView/화면 유지 정책을 포함한 JVM/unit 수준이며 ARCore field/instrumentation 근거가 아니다. |
| Current dataset | train 167,759, val 28,747, boxes 607,814, validator PASS | 구조/라벨 형식 근거이며 corrupt decode와 독립 test 성능을 증명하지 않는다. |
| 분류 manifest | 초기 FAIL -> 재생성 후 PASS | 삭제된 dataset/source-eval 경로를 제거하고 153,919 repo rows/86 Downloads rows를 현재 구조로 재생성했다. |

## 우선순위

### P0: Web/PWA 제품 판정을 막는 항목

1. `curb_step`, `uneven_sidewalk` 라벨 기준과 corrupt data를 정리하고 독립 평가셋으로 재검증한다.
2. 품질 gate를 통과한 unified 모델을 backend real provider에 연결하고 Web 기본 탐지 설정을 운영값으로 고정한다.
3. PWA 등록, HTTPS/domain 배포와 모바일 브라우저 camera/GPS/mic/TTS/진동/길안내 E2E를 검증한다.
4. 전용 로컬 PostGIS `walksafe_test` DB의 no-skip 회귀를 정기 유지하고 운영 DB의 관리자 auth/RBAC·audit·복구를 검증한다.

### P1: 원안 핵심 기능의 빈 부분

1. Web 목적지/후보/길안내 음성 흐름을 모바일 브라우저에서 현장 검증한다.
2. IMU+GPS 정밀 측위를 구현할지 현재 GPS 품질 gate로 요구를 바꿀지 결정한다.
3. 실제 map/heatmap UI와 운영 auth/RBAC/audit을 구현한다.
4. app shell 수준인 offline PWA를 탐지·신고·길안내 실패 정책과 함께 제품 수준으로 검증한다.

### P2: 외부 승인과 운영비가 필요한 항목

- S3/모델 registry/자동 재학습/무중단 배포 MLOps
- 공개 데이터셋 운영
- 실서비스 domain/HTTPS/storage/monitoring/rollback

## 문서 분류 기준

| 분류 | 현재 위치 | 사용 방법 |
|---|---|---|
| 원안 기준 | `docs/submission/source_materials/` | 계약·목표의 출발점. 현재 구현 완료 근거로 쓰지 않는다. |
| 현재 상태 | 이 문서, `docs/status/current_status.md` | 구현/미구현/검증 수준 판단의 첫 문서다. |
| 현재 정책 | `product/`, `docs/walksafe-v2/`, `docs/operations/` | 내부 기술결정과 운영 정책이다. 원안 변경 승인과는 구분한다. |
| 구현 설명 | 코드 폴더별 `README.md`, `docs/android/`, `docs/backend/`, `docs/model-data/` | 모듈 책임과 실행 계약을 확인한다. |
| 검증 근거 | `docs/evidence/`, `reports/`, `docs/execution/`, `daylog/` | 명시된 날짜·환경·등급 범위에서만 인용한다. |
| 과거/대체됨 | `docs/_archive_candidates/2026-07-08/`, `docs/design/`의 HISTORICAL 문서 | 맥락 보존용이며 새 구현 기준으로 쓰지 않는다. |
| 로컬 전용 | `datasets/`, `runs/`, `logs/`, `model/artifacts/` | 대형 데이터·모델·실행 산출물. current 문서와 분리한다. |

## 완료 주장 금지

- Android build/unit PASS를 ARCore field PASS로 쓰지 않는다.
- PT 학습 완료를 final TFLite/실사용 모델 완료로 쓰지 않는다.
- 로컬 PostGIS test와 USB phone smoke를 운영 DB·보안·Web 모바일 E2E 완료로 쓰지 않는다.
- Web/PWA policy PASS를 설치·오프라인·실서비스 release PASS로 쓰지 않는다.
- Android 보조 연구 결과를 Web/PWA Release·mobile field 완료 근거로 쓰지 않는다.
- 수동 학습 스크립트를 자동 MLOps로 표현하지 않는다.
