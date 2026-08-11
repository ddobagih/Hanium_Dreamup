# WalkSafe cleanup inventory - 2026-07-04

작성 기준: 2026-07-04 KST, 사용자 정책 기준.

이 문서는 "현재 기능이 구현된 뒤 남은 코드/문서 잔재"를 정리하기 위한 인벤토리다. 삭제 작업 자체의 완료 문서가 아니라, 무엇을 바로 지우고 무엇을 final model/운영 gate 전까지 격리할지 구분한다.

## 사용자 정책 기준

1. 주 사용자 앱은 Android native다.
2. PWA/Web WalkSafe 화면은 demo/API 검증/support 경로다. 사용자가 제품 앱으로 오해하면 안 된다.
3. fake/demo detector와 fake report는 운영/성능/안전 판단 경로에 섞지 않는다.
4. 기관 제출 기능은 없다. 운영자 내부 list/export만 있다.
5. 신호등 색상 STOP은 `DEFERRED`다. 현재 모델의 `traffic light`는 객체 검출일 뿐 빨강/초록 판단이 아니다.
6. 최종 unified TFLite asset이 들어오기 전까지 legacy fallback은 호환용으로 유지하되, 완료 기능처럼 보이면 안 된다.
7. 운영 auth/RBAC/rate-limit/signed URL/audit/secret은 출시 lane이다. 임시 local gate를 운영 보안으로 과장하지 않는다.
8. debug/test capture는 developer/dev/test surface에만 있어야 한다.

## 2026-07-04에 바로 제거한 코드 잔재

| 영역 | 항목 | 처리 |
|---|---|---|
| Android depth | `DebugCenterDetectionProvider` | 참조 없는 fake center bbox provider 삭제 |
| Android report | `reportPlaceholderImageBytes`, `reportPlaceholderImage()` | 호출 없는 1x1 JPEG placeholder 삭제 |
| Android overlay | `DebugBboxOverlayView.update(...)` | 현재 `updateMapped()`만 쓰므로 old API 삭제 |
| Android detector | `shouldRunCustom = true` unreachable branch | 항상 true인 죽은 분기 삭제 |
| Android detector API | `forceCustom` parameter | 실제 동작이 없어 interface/call site에서 삭제 |
| Android debug manifest | debug `INTERNET` permission 중복 | main manifest에 있으므로 debug 중복 선언 삭제 |
| Android depth smoke | `DepthSmokePipeline` | production source에서 test source로 이동 |
| Android feedback | `traffic light` user-facing STOP/WARNING | 신호등 색상 정책 전까지 사용자 위험 안내에서 제외 |
| Backend/Web admin | `performance_excluded`를 fake/demo로 분류 | 성능 제외와 fake/demo 판별을 분리 |
| Web test capture | production에서 env만 켜면 저장 가능 | `NODE_ENV=production` hard block 추가 |
| Verification script | depth scaffold가 test-only smoke helper를 main source에서 요구 | 현재 source layout 기준으로 갱신 |

## 다음에 바로 정리 가능한 코드 후보

| 우선순위 | 영역 | 항목 | 조치 기준 |
|---|---|---|---|
| P1 | Android overlay | debug bbox overlay release 노출 | 제품 release에서는 숨기거나 developer option 뒤로 이동 |
| P1 | Android debug | 서버 로그/캡처 버튼 release 노출 | `BuildConfig.DEBUG` 또는 explicit dev surface 뒤로 이동 |
| P2 | Android runtime config | JSON `policy` block | Kotlin에서 읽지 않으면 삭제. source-of-truth로 쓸 거면 parser/test 추가 |
| P2 | Android class map | `TwoModelClassMap` | runtime source-of-truth가 JSON이면 test fixture로 이동 |

## 최종 모델 투입 뒤 제거/축소할 코드 후보

| 영역 | 현재 유지 이유 | 제거 조건 |
|---|---|---|
| legacy two-model fallback | unified TFLite asset이 아직 없어 Android 실사용 탐지 fallback으로 필요 | final/export-ready unified TFLite asset 투입, 정지 smoke, Android metadata에서 `loaded_model_key=unified_walksafe` 확인 |
| `custom_tactile`/`coco_general` report compatibility | legacy Android/backend report replay와 테스트 호환 | unified-only 운영 전환 결정 후 v2 API deprecation plan 작성 |
| backend legacy `/detect`/`/reports` v1 | PWA/support 경로와 과거 테스트가 아직 참조 | Android/v2-only 전환 후 deprecation window를 두고 제거 |
| `onnx` source bucket | 과거 Web/ONNX 계획 흔적 | ONNX 운영 계획이 없다고 확정하면 schema/admin/filter/export에서 제거 |

## 운영 출시 전 반드시 격리할 후보

| 영역 | 잔재 | 조치 |
|---|---|---|
| Backend detect | `DETECT_V2_MODE=fake` 기본값 | 운영 env에서는 fail-closed 또는 explicit demo opt-in |
| Backend/Web fake 판단 | fake/demo 판별 로직이 SQL, serialization, frontend에 중복 | 공통 정책 함수/계약으로 통합. `performance_excluded`와 `fake/demo`를 분리 |
| Reporter/auth | `reporter_user_id` optional, Android local user id gate | 실제 auth subject 기반 reporter로 교체. local input은 dev/demo로 제한 |
| Debug API | `/android/debug/*` router include | 운영에서는 router include 자체를 env/auth gate 뒤로 이동 |
| Upload access | report image path 조회 | 운영 signed URL/RBAC 전에는 외부 공개 금지 |
| Retention | dry-run만 있음 | 180일 delete job은 backup/audit/dry-run 확인 뒤 운영에서 enable |

## PWA/Web/voice 제품 표면 정리 후보

| 영역 | 잔재 | 조치 |
|---|---|---|
| PWA metadata | `layout.tsx`가 PWA prototype/manifest를 항상 노출 | Android native primary와 demo/support 성격이 드러나게 문구/manifest 노출 조정 |
| PWA manifest | standalone app처럼 보이는 `manifest.webmanifest` | PWA를 유지한다면 demo/support app으로 명명. 제품 앱 오해 제거 |
| PWA install/offline UI | `AssistPanel`의 앱 설치/오프라인 버튼 | 제품 화면에서 제거하거나 demo/dev mode에서만 노출 |
| Service worker | 오래된 version label | opt-in 유지 시 현재 정책명으로 갱신 |
| Web voice client | local voice server 기본값 | Android native voice가 주 경로이므로 prototype/docs lane으로 분리 |
| `voice/server.py` | local STT/TTS prototype server | 삭제보다 `prototype`/`tools` 성격을 명확히 하고 운영 build에서 제외 |
| TestCapturePanel | 이미지/metadata 저장 도구 | 유지하되 dev/test evidence tool로만 노출 |

## 문서 정리 후보

| 문서군 | 상태 | 조치 |
|---|---|---|
| `*_3day_execution_plan.md` | 과거 계획/이력 | 최신 source-of-truth가 아님을 유지하고 archive 후보로 관리 |
| `docs/execution/**` | 실행 이력 | 삭제하지 않고 archive/history로 유지 |
| old PWA/voice docs | Android native 이전 계획 포함 | `docs/README.md`의 superseded 분류를 기준으로 최신 문서와 분리 |
| agency/submission wording | 자동 제출 기능 없음과 충돌 가능 | "기관 제출"이 아니라 "운영자 내부 export"로 통일 |
| AIHub/ARCore 표현 | offline ZED reference와 Android ARCore PASS 혼동 가능 | `source_kind=offline_zed_reference`, `arcore_pass=false` 유지 |

## 제거 금지 또는 보류

| 항목 | 이유 |
|---|---|
| `NoopAndroidFrameDetector` | asset 부재/로드 실패 시 안전 fallback |
| legacy model fallback | unified asset 전까지 실제 Android detector fallback |
| PostGIS skip | 로컬 DB 없는 환경 호환. no-skip은 별도 DB 검증 lane |
| AIHub189 evaluator | 원본 full offline validation evidence 재현 경로 |
| debug/test capture backend | dev/test evidence 수집 도구. 단 운영 surface에서는 격리 필요 |

## 권장 실행 순서

1. Android dead code 제거와 Gradle unit test.
2. PWA 제품 표면에서 install/offline/manifest 문구를 demo/support로 축소.
3. fake/demo 판별 정책을 `fake/demo`와 `performance_excluded`로 분리.
4. 운영 env에서 fake detect mode fail-closed.
5. final unified TFLite asset 투입 후 legacy fallback 제거 여부 결정.
6. auth/RBAC 적용 후 local reporter gate 제거.
