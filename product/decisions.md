# Product Decisions

## 결정된 사항

| 날짜 | 결정 | 이유 | 영향 범위 | 근거 |
|---|---|---|---|---|
| 2026-08-30 | 첫 실행 4·5단계의 본인확인을 이메일 회원가입·이메일 인증으로 **한시적 대체**한다. `RQ-FP-010-001`의 「회원가입과 휴대전화 확인」은 **변경하지 않는다**. 전환 조건: 출시 전에 본인확인(SMS)으로 되돌리며, 그때까지 이 대체 상태를 출시 근거로 쓰지 않는다 | 본인확인 API는 건당 비용과 계약 주체가 필요해 개발·시험 단계에서 확보하기 어렵다. 이메일은 SMTP로 비용 없이 같은 단계 구조를 검증할 수 있다 | backend 가입·인증 API, Gateway 프록시, Android 4·5단계 입력·문구, 요구사항 추적 | 팀원 전달: 2026-08-30. 요구 원문은 `docs/deliverables/03-requirements/system-requirements.md:1065` 그대로 유지 |
| 2026-07-11 | 목적지까지 전역 경로는 TMAP으로 정하고, 정상 점자블록이 3 frame·700ms·confidence 0.55·freshness 1,200ms·GPS 25m·heading 35°·camera corridor gate를 모두 통과한 동안만 국소 단거리 steering 목표로 우선한다. 손상·불확실·미검출이면 TMAP으로 복귀한다 | 사용자가 원하는 점자블록 우선 보행과 지도 경로의 안정적인 목적지 도달을 함께 유지하기 위함 | Web navigation, Android 연구 navigation, 테스트·제출 문서 | 사용자 확인: 2026-07-11 |
| 2026-07-11 | epoch270 img768 PT에서 export한 unified float32 13-class TFLite를 Android 개발 runtime primary로 적용한다. asset/tensor/hash 검증과 Device Field·Release 승인은 구분한다 | 최신 768 후보를 backend뿐 아니라 Android 연구 runtime에도 같은 class 계약으로 적용하기 위함 | model deployment record, Android runtime config, 제출 문서 | PT SHA-256 `a38857e...`, TFLite SHA-256 `92b39d3b...` |
| 2026-07-11 | 목적지 검색과 보행 길안내 provider는 TMAP API로 고정하고 다른 provider 설정·응답은 거부한다 | 현재 제품이 실제 사용하는 외부 길안내 계약을 하나로 명확히 하기 위함 | navigation, 환경설정, 제출·운영 문서 | 사용자 확인: 2026-07-11 |
| 2026-07-11 | 13개 class는 모두 핵심 class로 유지하되 역할을 분리한다. 손상 점자블록만 자동 신고, 정상 점자블록은 경로 보조, 신호등은 존재 탐지, 나머지 보행 방해 객체·노면은 경고/경로 판단에 사용한다 | class 삭제 문제가 아니라 제품 행동을 class 의미에 맞게 분리해야 함 | detector, risk, navigation, report, 현장 체크리스트 | 사용자 확인: 2026-07-11, `docs/walksafe-v2/policy_decisions_20260702.md` |
| 2026-07-11 | 내일 field build는 epoch270 13-class PT를 real/img768/단일 모델/fake fallback 없음으로 사용한다 | 최신 학습 모델을 실제 원거리 시험에 적용하라는 사용자 요청 | backend runner, Web production profile, model registry | `configs/walksafe_unified_epoch270_field_20260711.json`, model SHA-256 검증 |
| 2026-07-11 | 현장 threshold·오탐·지연 출시 하한은 임시 관찰값으로 시작하고 실제 외출 로그 뒤 확정한다 | 현재 독립 field 근거가 없으므로 임의의 출시 승인값을 확정하지 않기 위함 | field config, telemetry, threshold playbook | 사용자 위임: 2026-07-11 |
| 2026-07-11 | 원거리 시험은 Cloudflare quick tunnel, 12시간 field/admin 분리 session, 실패 login rate limit, metadata 7일 보존으로 운영한다. 원본 카메라·음성은 자동 저장하지 않고 명시 스냅샷만 저장한다 | 내일 테스트 가능성과 임시 공개 주소의 접근·개인정보 위험을 함께 관리 | Web gateway, field telemetry, 운영 문서 | `docs/testing/web_remote_field_test_20260711.md` |
| 2026-07-10 | 주 사용자 앱은 Web/PWA다. Android native는 ARCore depth와 기기 내 TFLite 연구·검증 보조 경로다 | 사용자 재확인과 원안 PWA 플랫폼이 일치하며, Android 구현량을 제품 플랫폼 결정으로 해석하면 안 됨 | README, roadmap, backlog, done criteria, 제출 문서 | 사용자 확인: 2026-07-10, `docs/status/implementation_audit_20260710.md` |
| 2026-07-10 | 공공기관 API 자동 연계는 하지 않는다. 관리자가 신고를 검수·필터링해 CSV로 내려받고 기관 외부 채널에 별도 수동 신고한다 | 사용할 수 있는 기관 API가 없고 human review가 필요함 | backend/admin/export, 운영·제출 문서 | 사용자 재확인: 2026-07-10, `docs/walksafe-v2/policy_decisions_20260702.md` P-13 |
| 2026-06-02 | 문서 source of truth는 `docs/README.md`의 canonical map으로 통합하고, stale 계획/실행 로그는 legacy/superseded로 본다 | 3-day 계획, 과거 execution, PWA/v1 문서가 Android/unified 기준과 충돌함 | docs, product, report evidence | `docs/README.md`, `docs/status/current_status.md` |
| 2026-06-02 | detector 기본 방향은 `unified_walksafe` 13-class primary + legacy two-model load-failure fallback으로 둔다 | 단일 unified 경로를 정상 실행으로 사용하되 asset load·tensor 계약 실패 시 연구 화면이 통제된 legacy pair로만 복구되게 함 | Android runtime, backend `/detect/v2`, model docs | 2026-07-11 Android unified 768 primary 적용으로 구체화 |
| 2026-05-31 | [SUPERSEDED 2026-07-10] 주 사용자 앱을 Android native로 본 내부 기술결정 | 당시 ARCore depth와 camera coordinate 검증을 제품 플랫폼 결정으로 확대 해석함 | 과거 Android 계획·snapshot에만 적용 | `apps/android/README.md`, 사용자 확인: 2026-07-10 |
| 2026-05-31 | Android bbox/depth 좌표 정합 gate 전에는 TTS/haptic/report upload를 제품 기능으로 추가하지 않는다 | 잘못된 bbox/depth는 잘못된 위험 안내와 신고로 이어질 수 있음 | Android runtime, report upload, voice/TTS 계획 | `docs/status/current_status.md`, `docs/android/android_device_overlay_depth_checklist_20260601.md` |
| 2026-05-31 | `model-config/two_model_runtime.json`은 Android runtime source of truth다 | asset path/input/class/allowlist/threshold가 Kotlin 상수와 분산되면 APK 동작을 추적하기 어려움 | Android TFLite export, detector, tests | `apps/android/README.md` |
| 2026-05-31 | Android TFLite threshold와 backend `/detect/v2` threshold는 같다고 가정하지 않는다 | 현재 check에서 threshold 차이가 warning으로 확인됨 | model evaluation, report 문구, config 문서 | `scripts/check_android_tflite_contract_20260531.py` |
| 2026-05-31 | STT/voice는 정책 의미를 유지하지만 Android native P0를 막는 별도 서버 필수조건이 아니다 | 현재 핵심 blocker는 bbox/depth coordinate gate이며 voice는 후속 UX 연결 | voice docs, roadmap, backlog | `docs/status/voice_stt_tts_status.md` |
| 2026-05-18 | `source=fake`는 UI/API/운영 흐름 검증용이며 성능·안전 근거로 쓰지 않는다 | fake detector는 실제 모델 결과가 아니므로 과대해석 위험 | 보고, 관리자 필터, 모델 성능 문서 | `docs/model_placeholder_systems.md`, `docs/operations/report_operations.md` |
| 2026-05-18 | legacy tactile v2 모델은 class `0: damaged_tactile_block` baseline으로만 본다 | 한국 GT class `1..3`가 없어 4-class 서비스 성능을 산출할 수 없음 | 모델 roadmap, 발표 문구, backlog | `docs/_archive_candidates/2026-07-08/docs/model_training_status.md`, `docs/_archive_candidates/2026-07-08/docs/model_v2_status.md` |
| 2026-05-18 | 공식 field test 착용 방식은 사원증처럼 휴대폰을 목에 거는 목걸이 방식으로 한다 | 실제 사용/시연 폼팩터를 사용자가 확정함 | Android field smoke, 카메라 각도, IMU ROI, done criteria | 사용자 확인: 2026-05-18 |
| 2026-05-18 | PostGIS runtime smoke는 disposable DB를 우선 사용한다 | 테스트 row/upload cleanup 실수와 운영 데이터 영향 위험을 줄이기 위함 | reports no-skip, HTTP smoke, duplicate/radius 검증 | 사용자 확인: 2026-05-18 |
| 2026-05-18 | PDF의 `정확도 90% 이상`, `경보 지연 1초 이내`는 목표로 기록하되 현재 완료 기준으로 쓰지 않는다 | metric 정의와 실기기 지연 근거가 필요함 | done criteria, 발표 문구 | PDF 2개, `docs/_archive_candidates/2026-07-08/docs/model_training_status.md` |
| 2026-05-18 | 10월 성과목표의 외부 연동은 demo/mock 우선으로 처리한다 | 실제 AWS/S3·Cloud STT/TTS·지자체 연동은 비용/secret/개인정보/외부기관 의존성이 큼 | M3 release, C 작업, 발표 문구 | 사용자 위임: 2026-05-18 |

## 현재 해석

- 플랫폼과 기관 신고 방식은 2026-07-10 사용자 확인으로 확정됐다.
- production PWA와 임시 HTTPS field profile은 준비됐지만 quick tunnel은 실서비스 도메인·가용성 근거가 아니다.
- local model registry, 승격·rollback·격리는 구현됐으나 완전 자동 재학습·무중단 배포 MLOps는 아니다.
- 현재 핵심 남은 근거는 실제 모바일 Web 현장 검증, 모델 약한 class 재검수, 정식 계정/RBAC와 운영 backup/retention이다.
- 첫 실행 4·5단계는 2026-08-30 결정에 따라 이메일로 대체 구현한다. 요구는 휴대전화 확인 그대로이므로 이 구간은 **요구 미충족 상태로 진행 중**이며, 출시 전 본인확인 전환이 완료되어야 `RQ-FP-010-001`을 만족했다고 말할 수 있다. 전환 시 구현에서 확인할 것:
  - `FirstRunOnboardingStage`의 `LOCAL_CREDENTIAL_PHONE_SUBMISSION`·`VERIFIED_SMS` **enum 이름은 바꾸지 않는다**. 단계 이름이 `firstRunLocalRequest`의 request·attempt id 해시에 들어가므로 개명하면 기존 설치의 복원과 영수증 연속성이 깨진다. 사용자에게 보이는 문구만 교체한다.
  - 앱의 4·8단계 증거는 불투명 핸들(`onb_…`, `actor_…`)과 영수증만 담고 사용자 입력에서 파생하지 않으므로, 채널이 이메일에서 SMS로 바뀌어도 앱 상태기계는 다시 바꾸지 않아도 된다.

## B - 사용자 확인 필요

현재 우선 B 항목은 없다. 단, 아래는 실행 전 사용자/환경이 필요하다.

| ID | 항목 | 필요한 것 |
|---|---|---|
| B-Android-001 | overlay/depth Device PASS | ARCore 지원 실기기 관찰 기록 |
| B-Data-001 | legacy reviewed tactile3 full rerun | 과거 비교가 필요할 때만 legacy image/GT dataset 복구. 현재 13-class dataset/eval과 구분 |
| B-Release-001 | release/domain/storage/secret | 계정/비용/도메인/배포 승인 |

## C - 위험/대형 작업

| ID | 작업 | 위험 | 필요한 승인/환경 | 현재 상태 |
|---|---|---|---|---|
| C-001 | 운영 DB migration/대량 데이터 변경 | 데이터 손실/운영 영향 | DB 접근, migration 승인, 백업/rollback | 보류 |
| C-002 | full static dataset 재구성/대량 평가 | 디스크/시간/대형 산출물 | dataset 복구, 디스크 gate | 보류 |
| C-003 | 이미지/깊이 파일 capture/export | 개인정보/용량 | 저장 정책, 마스킹, 삭제 기준 | metadata-only 우선 |
| C-004 | 외부 지도/API 고도화 | 비용/secret/약관 | key/계정/권한 확인 | 보류 |
| C-005 | 지자체 민원/안전신문고 실제 API 연계 | 외부기관/개인정보/법적 책임 | 현재 사용 가능한 API 없음 | 제품 범위 밖. 관리자 CSV 수동 신고 사용 |
| C-006 | Cloud STT/TTS 또는 유료 API 사용 | 비용/API key/개인정보 전송 | 계정/비용/약관/키 관리 | 보류 |
| C-007 | 실서비스 배포/Play Console/도메인 | 외부 공개/운영 책임 | 배포 승인, rollback | 보류 |

## 문서 충돌 최신 해석

| 충돌 | 최신 해석 | 근거 |
|---|---|---|
| Android가 주 앱이라는 2026-05-31~07-10 내부 표현 | Web/PWA가 주 사용자 앱이고 Android는 ARCore/TFLite 보조 연구 경로 | 사용자 확인: 2026-07-10, `README.md`, `docs/status/current_status.md` |
| STT 서버가 Android 후속 기능처럼 보이는 표현 | Web의 browser MediaRecorder→local STT/rule intent와 browser TTS가 주 앱 흐름이다. 목적지 변경·취소·다음 안내 질의까지 연결됐고 모바일 mic/TTS E2E가 남음 | `docs/status/voice_stt_tts_status.md` |
| `/detect/v2` fake만 있다는 표현 | backend는 fake 기본 + yolo/real lazy provider이며 Web `server-v2`의 실제 탐지 경로 | `docs/status/current_status.md`, `apps/web/README.md` |
| COCO/custom two-model이 현재 기본이라는 표현 | 2026-07-11 기준 epoch270 기반 unified 768 13-class TFLite가 Android 개발 runtime의 expected primary다. custom tactile+COCO는 unified load·hash·tensor 실패 시에만 사용하는 legacy fallback이다 | `apps/android/README.md`, `docs/status/current_status.md`, `model/deployments/local-deployment.json` |
| APK 경로/설치 기준 불명확 | debug APK가 생성되어 있고 절대 설치 경로와 SHA-256을 문서화 | `apps/android/README.md` |
| depth sensor 미구현 표현 | Android ARCore depth snapshot은 연결됨. 다만 bbox-depth 좌표 정합과 `N보` 실측은 미검증 | `docs/android/arcore_depth_estimation_architecture.md` |
| legacy reviewed dataset full metric 가능처럼 보이는 표현 | legacy tactile3 image/GT는 없어 과거 비교가 blocked다. 현재 13-class dataset validation/eval은 별도로 완료됐다 | `docs/execution/2026-05-31_android_static_dataset_contract_progress.md`, `reports/walksafe_best_eval_20260708/final_evaluation_report.md` |

## 과대해석 방지 보고

완료로 쓰지 않는 항목:

- Android build PASS를 overlay/depth Device PASS로 쓰지 않음.
- `track/person` 또는 `1보` 표시 관찰을 거리 정확도 PASS로 쓰지 않음.
- static RGB 결과를 ARCore depth/`N보` 정확도 근거로 쓰지 않음.
- fake/headless 결과를 Web/PWA 모바일 보행 안전 근거로 쓰지 않음.
- STT local sample 성공을 모바일 브라우저 mic/TTS 제품 완료로 쓰지 않음.
- 외부 배포/비용 발생/API 연동/공공기관 제출을 승인 없이 진행하지 않음.
