# WalkSafe cleanup inventory - 2026-07-04

작성 기준: 2026-07-04 KST, 2026-07-10 플랫폼·기관 운영 역할 보정.

이 문서는 "현재 기능이 구현된 뒤 남은 코드/문서 잔재"를 정리하기 위한 인벤토리다. 삭제 작업 자체의 완료 문서가 아니라, 무엇을 바로 지우고 무엇을 final model/운영 gate 전까지 격리할지 구분한다.

## 사용자 정책 기준

1. 주 사용자 앱은 Web/PWA다.
2. Android native는 ARCore/depth/TFLite 실험·검증 보조 경로다. Android Device evidence를 Web/PWA 제품 완료로 해석하지 않는다.
3. fake/demo detector와 fake report는 운영/성능/안전 판단 경로에 섞지 않는다.
4. 기관 자동 API 제출은 없다. 관리자가 신고를 검수·필터링한 뒤 named 검수된 reviewed·damage·high≤15m·non-fake의 현재 필터 최대 10,000행을 정확 위치 `agency` CSV로 내려받아 외부 기관에 수동 신고한다. 초과 시 413이며 선택·병합 기능은 없다.
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
| backend legacy `/detect`/`/reports` v1 | Web/PWA와 과거 테스트가 아직 참조 | Web/PWA의 `server-v2` 전환과 회귀 검증 후 deprecation window를 두고 제거 |
| `onnx` source bucket | 과거 Web/ONNX 계획 흔적 | ONNX 운영 계획이 없다고 확정하면 schema/admin/filter/export에서 제거 |

## 운영 출시 전 반드시 격리할 후보

| 영역 | 잔재 | 조치 |
|---|---|---|
| Backend detect | `DETECT_V2_MODE=fake` 기본값 | 운영 env에서는 fail-closed 또는 explicit demo opt-in |
| Backend/Web fake 판단 | fake/demo 판별 로직이 SQL, serialization, frontend에 중복 | 공통 정책 함수/계약으로 통합. `performance_excluded`와 `fake/demo`를 분리 |
| Reporter/auth | `reporter_user_id` optional, Android local user id gate | Web/PWA의 실제 auth subject 기반 reporter로 교체. Android local input은 실험·개발 경로로 제한 |
| Debug API | `/android/debug/*` router include | 운영에서는 router include 자체를 env/auth gate 뒤로 이동 |
| Upload access | report image path 조회 | 운영 signed URL/RBAC 전에는 외부 공개 금지 |
| Retention | dry-run만 있음 | 180일 delete job은 backup/audit/dry-run 확인 뒤 운영에서 enable |

## PWA/Web/voice 제품 표면 정리 후보

| 영역 | 잔재 | 조치 |
|---|---|---|
| PWA metadata | `layout.tsx`와 manifest의 제품명/설명 | Web/PWA 주 사용자 앱 이름과 접근성 목적을 일관되게 표시 |
| PWA manifest | `manifest.webmanifest`와 설치 경로 | standalone 설치 동작, 아이콘, start URL을 Release evidence로 검증 |
| PWA install/offline UI | `AssistPanel`의 앱 설치/오프라인 버튼 | 제품 흐름에 연결하고 설치 불가·업데이트·오프라인 실패 상태를 실폰에서 검증 |
| Service worker | opt-in 설정과 오래된 version label | 배포 정책에 맞게 활성 여부를 확정하고 현재 정책명과 cache version으로 갱신 |
| Web voice client | local voice server 기본값 | Web/PWA 주경로의 개발용 STT provider임을 명시하고 운영 provider·실폰 E2E를 별도 검증 |
| `voice/server.py` | local STT/TTS prototype server | 삭제보다 `prototype`/`tools` 성격을 명확히 하고 운영 build에서 제외 |
| TestCapturePanel | 이미지/metadata 저장 도구 | 유지하되 dev/test evidence tool로만 노출 |

## 문서 정리 후보

| 문서군 | 상태 | 조치 |
|---|---|---|
| `*_3day_execution_plan.md` | 과거 계획/이력 | 최신 source-of-truth가 아님을 유지하고 archive 후보로 관리 |
| `docs/execution/**` | 실행 이력 | 삭제하지 않고 archive/history로 유지 |
| old PWA/voice docs | v1/fake 계약 또는 기간 만료 계획 포함 | 플랫폼이 아니라 API/model/UI 버전과 작성일을 기준으로 최신 문서와 분리 |
| agency/submission wording | 자동 API 제출과 수동 외부 신고가 혼동될 수 있음 | "관리자 검수·필터 → CSV 다운로드 → 수동 외부 신고"로 통일 |
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

1. Web/PWA 제품 표면의 manifest/install/offline 상태와 브라우저·실폰 검증 기준을 정리한다.
2. fake/demo 판별 정책을 `fake/demo`와 `performance_excluded`로 분리한다.
3. 운영 env에서 fake detect mode를 fail-closed로 둔다.
4. 관리자 검수·필터와 CSV 수동 외부 신고 흐름을 검증한다.
5. Android dead code 제거와 Gradle unit test를 별도 실험·검증 lane에서 유지한다.
6. final unified TFLite asset 투입 후 Android legacy fallback 제거 여부를 결정한다.
7. auth/RBAC 적용 후 local reporter gate를 제거한다.
