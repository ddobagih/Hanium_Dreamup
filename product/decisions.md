# Product Decisions

## 결정된 사항

| 날짜 | 결정 | 이유 | 영향 범위 | 근거 |
|---|---|---|---|---|
| 2026-06-02 | 문서 source of truth는 `docs/README.md`의 canonical map으로 통합하고, stale 계획/실행 로그는 legacy/superseded로 본다 | 3-day 계획, 과거 execution, PWA/v1 문서가 Android/unified 기준과 충돌함 | docs, product, report evidence | `docs/README.md`, `docs/current_status.md` |
| 2026-06-02 | detector 기본 방향은 `unified_walksafe` 13-class primary + legacy two-model fallback으로 둔다 | 점자블록/COCO 병렬 runtime 지연과 class 확장 문제를 단일 모델 drop-in으로 줄이기 위함 | Android runtime, backend `/detect/v2`, model docs | `apps/android/README.md`, `docs/walksafe-v2/two_model_runtime_plan.md`, `docs/model_unified_13class_aihub_sources_20260602.md` |
| 2026-05-31 | 주 사용자 앱 경로는 Android native ARCore/TFLite APK로 둔다. Web/PWA는 demo/admin/ops/과거 호환 보조 경로다 | Web에서 억지로 `N보`를 만드는 대신 ARCore depth와 실기기 camera coordinate를 검증해야 함 | roadmap, backlog, done criteria, docs/current_status | `apps/android/README.md`, `docs/current_status.md` |
| 2026-05-31 | Android bbox/depth 좌표 정합 gate 전에는 TTS/haptic/report upload를 제품 기능으로 추가하지 않는다 | 잘못된 bbox/depth는 잘못된 위험 안내와 신고로 이어질 수 있음 | Android runtime, report upload, voice/TTS 계획 | `docs/current_status.md`, `docs/android/android_device_overlay_depth_checklist_20260601.md` |
| 2026-05-31 | `model-config/two_model_runtime.json`은 Android runtime source of truth다 | asset path/input/class/allowlist/threshold가 Kotlin 상수와 분산되면 APK 동작을 추적하기 어려움 | Android TFLite export, detector, tests | `apps/android/README.md` |
| 2026-05-31 | Android TFLite threshold와 backend `/detect/v2` threshold는 같다고 가정하지 않는다 | 현재 check에서 threshold 차이가 warning으로 확인됨 | model evaluation, report 문구, config 문서 | `scripts/check_android_tflite_contract_20260531.py` |
| 2026-05-31 | STT/voice는 정책 의미를 유지하지만 Android native P0를 막는 별도 서버 필수조건이 아니다 | 현재 핵심 blocker는 bbox/depth coordinate gate이며 voice는 후속 UX 연결 | voice docs, roadmap, backlog | `docs/voice_stt_tts_status.md` |
| 2026-05-18 | `source=fake`는 UI/API/운영 흐름 검증용이며 성능·안전 근거로 쓰지 않는다 | fake detector는 실제 모델 결과가 아니므로 과대해석 위험 | 보고, 관리자 필터, 모델 성능 문서 | `docs/model_placeholder_systems.md`, `docs/report_operations.md` |
| 2026-05-18 | legacy tactile v2 모델은 class `0: damaged_tactile_block` baseline으로만 본다 | 한국 GT class `1..3`가 없어 4-class 서비스 성능을 산출할 수 없음 | 모델 roadmap, 발표 문구, backlog | `docs/model_training_status.md`, `docs/model_v2_status.md` |
| 2026-05-18 | 공식 field test 착용 방식은 사원증처럼 휴대폰을 목에 거는 목걸이 방식으로 한다 | 실제 사용/시연 폼팩터를 사용자가 확정함 | Android field smoke, 카메라 각도, IMU ROI, done criteria | 사용자 확인: 2026-05-18 |
| 2026-05-18 | PostGIS runtime smoke는 disposable DB를 우선 사용한다 | 테스트 row/upload cleanup 실수와 운영 데이터 영향 위험을 줄이기 위함 | reports no-skip, HTTP smoke, duplicate/radius 검증 | 사용자 확인: 2026-05-18 |
| 2026-05-24 | 보행 길안내 기본 provider는 TMAP 보행자 경로안내로 둔다. Kakao Mobility는 제휴/권한 확보 후 후순위 fallback 후보로만 유지한다 | Kakao Mobility 도보 API는 현재 키에서 403 `permission denied`가 확인됐고, TMAP proxy 초안이 구현되어 있기 때문 | navigation, P2, release C 작업 | `docs/walksafe-v2/navigation_integration_policy.md`, `docs/current_status.md` |
| 2026-05-18 | PDF의 `정확도 90% 이상`, `경보 지연 1초 이내`는 목표로 기록하되 현재 완료 기준으로 쓰지 않는다 | metric 정의와 실기기 지연 근거가 필요함 | done criteria, 발표 문구 | PDF 2개, `docs/model_training_status.md` |
| 2026-05-18 | 10월 성과목표의 외부 연동은 demo/mock 우선으로 처리한다 | 실제 AWS/S3·Cloud STT/TTS·지자체 연동은 비용/secret/개인정보/외부기관 의존성이 큼 | M3 release, C 작업, 발표 문구 | 사용자 위임: 2026-05-18 |

## 우선 질문

- 현재 product 범위 결정을 위해 추가로 물어볼 질문은 없다.
- 남은 막힘은 주로 실행 환경/데이터다: ARCore 실기기 관찰, RGB-D/실측 거리, reviewed static dataset 복구, disposable DB.

## B - 사용자 확인 필요

현재 우선 B 항목은 없다. 단, 아래는 실행 전 사용자/환경이 필요하다.

| ID | 항목 | 필요한 것 |
|---|---|---|
| B-Android-001 | overlay/depth Device PASS | ARCore 지원 실기기 관찰 기록 |
| B-Data-001 | reviewed static full rerun | reviewed tactile3 원본 image/GT dataset |
| B-Release-001 | release/domain/storage/secret | 계정/비용/도메인/배포 승인 |

## C - 위험/대형 작업

| ID | 작업 | 위험 | 필요한 승인/환경 | 현재 상태 |
|---|---|---|---|---|
| C-001 | 운영 DB migration/대량 데이터 변경 | 데이터 손실/운영 영향 | DB 접근, migration 승인, 백업/rollback | 보류 |
| C-002 | full static dataset 재구성/대량 평가 | 디스크/시간/대형 산출물 | dataset 복구, 디스크 gate | 보류 |
| C-003 | 이미지/깊이 파일 capture/export | 개인정보/용량 | 저장 정책, 마스킹, 삭제 기준 | metadata-only 우선 |
| C-004 | 외부 지도/API 고도화 | 비용/secret/약관 | key/계정/권한 확인 | 보류 |
| C-005 | 지자체 민원/안전신문고 실제 API 연계 | 외부기관/개인정보/법적 책임 | 기관/API/제출 형식 승인 | 보류 |
| C-006 | Cloud STT/TTS 또는 유료 API 사용 | 비용/API key/개인정보 전송 | 계정/비용/약관/키 관리 | 보류 |
| C-007 | 실서비스 배포/Play Console/도메인 | 외부 공개/운영 책임 | 배포 승인, rollback | 보류 |

## 문서 충돌 최신 해석

| 충돌 | 최신 해석 | 근거 |
|---|---|---|
| PWA가 주 앱이라는 오래된 표현 | 2026-06-02 기준 Android native ARCore APK가 주 경로 | `README.md`, `docs/current_status.md` |
| STT 서버가 P0처럼 보이는 표현 | STT/voice는 prototype/후속 UX이며 Android bbox/depth gate를 막지 않음 | `docs/voice_stt_tts_status.md` |
| `/detect/v2` fake만 있다는 표현 | backend는 fake 기본 + yolo/real lazy provider, Android는 local TFLite primary | `docs/current_status.md`, `apps/android/README.md` |
| COCO/custom two-model이 기본이라는 표현 | 2026-06-02 기준 `unified_walksafe` 13-class가 primary이고, custom tactile+COCO는 legacy fallback | `apps/android/README.md`, `docs/current_status.md` |
| APK 경로/설치 기준 불명확 | debug APK가 생성되어 있고 절대 설치 경로와 SHA-256을 문서화 | `apps/android/README.md` |
| depth sensor 미구현 표현 | Android ARCore depth snapshot은 연결됨. 다만 bbox-depth 좌표 정합과 `N보` 실측은 미검증 | `docs/android/arcore_depth_estimation_architecture.md` |
| reviewed dataset full metric 가능처럼 보이는 표현 | 현재 로컬에는 full rerun에 필요한 reviewed tactile3 image/GT dataset이 없어 blocked | `docs/execution/2026-05-31_android_static_dataset_contract_progress.md` |

## 과대해석 방지 보고

완료로 쓰지 않는 항목:

- Android build PASS를 overlay/depth Device PASS로 쓰지 않음.
- `track/person` 또는 `1보` 표시 관찰을 거리 정확도 PASS로 쓰지 않음.
- static RGB 결과를 ARCore depth/`N보` 정확도 근거로 쓰지 않음.
- fake/PWA/headless 결과를 실제 보행 안전 근거로 쓰지 않음.
- STT local sample 성공을 Android mic/TTS 제품 완료로 쓰지 않음.
- 외부 배포/비용 발생/API 연동/공공기관 제출을 승인 없이 진행하지 않음.
