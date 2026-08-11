# WalkSafe documentation/code consistency audit - 2026-07-01

## 목적

WalkSafe 문서가 현재 코드, 설정, 스크립트, 앱 구조, 데이터/학습 파이프라인과 맞는지 확인하고, 이번 정리에서 갱신한 범위와 남은 리스크를 기록한다.

## 현재 상태

| 영역 | 코드 기준 현재 상태 | 문서 반영 |
|---|---|---|
| Android primary app | `apps/android`가 주 사용자 앱이다. APK hash는 `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`다 | `README.md`, `apps/android/README.md`, `docs/current_status.md`, Android evidence 문서 갱신 |
| Android TFLite runtime | `two_model_runtime.json`은 `unified_walksafe` primary + legacy fallback이다. unified asset은 아직 없고 config는 640 asset path를 유지한다 | Android/model 문서에 "768 학습 후보와 640 config 유지"를 분리 |
| Android report/TTS/haptic | Device Gate 뒤 TTS/haptic과 Android `/reports/v2` upload path가 코드에 있다. report는 damage-only, trusted GPS, fresh metric depth, allowed model key 조건을 사용한다. onPause release와 multipart writer 테스트가 추가됐다 | "gate 이후 붙일 예정" 문구를 "코드 연결, Device/DB 검증 필요"로 수정 |
| Backend/API | `source=android`, Android metadata allowlist/fallback fields, `/reports/v2`, summary/export/admin source count, route guide `bearing_deg`가 코드에 있다 | backend contract, report operations, Android source metadata decision 갱신 |
| Web/PWA | PWA는 여전히 `/` 루트에서 wide demo/support 기능을 제공한다. Android Device evidence를 대체하지 않는다 | docs map과 current status에서 보조 경로로 유지 |
| Model/data | 13-class AIHub183 PNG dataset과 768 학습 스크립트가 있다. strict 768 e300 run은 진행 중이며 최종 metric이 아니다 | `model/README.md`, `docs/current_status.md`에 진행 중/미완료로 반영 |

## 관련 파일/경로

- Android: `apps/android/`, `apps/android/app/src/main/assets/model-config/two_model_runtime.json`
- Android report: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/`, `MainActivity.kt`
- Backend/API: `backend/app/api/reports.py`, `backend/app/schemas.py`, `backend/app/services/report_policy.py`
- Web/PWA: `apps/web/app/_walksafe/`, `apps/web/lib/`, `apps/web/types/`
- Model/data: `model/README.md`, `scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh`, `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml`
- Canonical docs: `README.md`, `docs/README.md`, `docs/current_status.md`, `docs/walksafe-v2/`, `apps/android/README.md`
- Archive candidate list: `docs/archive_candidates_20260701.md`

## 확인한 주요 불일치와 처리

| 불일치 | 근거 | 처리 |
|---|---|---|
| APK hash가 `305f868...`/`8815c0...`/`ce9f08...`/`39d79e...` 이력과 현재 `2f6136...`로 혼재 | `sha256sum apps/android/app/build/outputs/apk/debug/app-debug.apk` | canonical/current evidence 문서는 `2f6136...`로 갱신. 과거 execution log는 이력으로 보존 |
| Android report upload가 "예정"으로 남음 | `AndroidReportUploader.kt`, `AndroidReportCandidatePolicy.kt`, `MainActivity.kt`, backend `ReportV2Metadata.source` | report docs를 "코드 연결, Device/DB 검증 필요"로 수정 |
| backend source enum 문서가 Android 미지원으로 남음 | `backend/app/schemas.py`, `apps/web/types/inference.ts`, `apps/web/app/admin/page.tsx` | backend contract/report operations/source decision 문서 갱신 |
| Android metadata allowlist 문서가 오래됨 | `REPORT_V2_METADATA_ALLOWLIST` | fallback/load reason 포함 allowlist 현행 key로 갱신 |
| 모델 문서가 640 단일 모델과 768 후보를 혼용 | 768 runner, training plan, Android config | "학습 후보는 768, Android config는 실제 768 TFLite 전까지 640 path 유지"로 분리 |
| PWA 기능 폭이 Android primary와 혼동될 수 있음 | PWA 루트 앱과 policy scripts | PWA는 demo/support/API 검증 경로라고 명시 |

## 실행/검증 방법

짧은 정합성 확인:

```bash
sha256sum apps/android/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/check_android_tflite_contract_20260531.py
python3 scripts/check_android_depth_scaffold_20260531.py
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. .venv/bin/python -m pytest backend/tests/test_android_debug_logs.py backend/tests/test_report_policy.py -q
cd apps/android && ./gradlew testDebugUnitTest --no-daemon
cd apps/web && npm run typecheck && npm run lint
```

문서 정합성 확인:

```bash
rg -n "305f8689153f05ead6d970e9b722523efe1555a81c9c6c8cd64c26a63fb261ba" README.md docs apps/android/README.md product
rg -n "Android native upload는 아직|upload 자체는 아직|source=server 유지|allowlist 없음" docs/walksafe-v2 docs/report_operations.md
```

## 남은 리스크

- Android Device PASS는 아직 없다. bbox overlay, depth sample, `N보`, TTS/진동, report upload는 실기기 기록이 필요하다.
- PostGIS가 떠 있지 않으면 report/export DB path tests는 skip될 수 있다.
- unified TFLite asset은 아직 APK에 없다. legacy fallback 동작과 unified 완료 주장을 분리해야 한다.
- strict 768 학습은 진행 중이며, truncated image warning과 최종 validation 결과를 별도로 감사해야 한다.
- 과거 execution/plans 문서는 링크 파손 위험 때문에 이동하지 않았다. `docs/archive_candidates_20260701.md`에만 분류했다.
