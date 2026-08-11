# Testing Documents

> 현재 제품은 Android 사용자 앱과 별도 Android 관리자 앱이다. 아래 Web/PWA 실행·릴리스 문서는 `LEGACY_REFERENCE_ONLY` 역사자료이며 현재 CI·현장시험·출시 증거로 실행하지 않는다. Phase D 당시 네 전환형 Android BFF가 있었지만 Phase E에서 독립 Gateway로 추출되고 Next에서 제거됐다. 아래에 보존된 release evidence 예시는 역사 설명이며 `web-release`·`full` profile은 코드 78로 차단된다.

이 폴더는 코드가 존재한다는 정적 판정과 실제 기기·현장 동작 판정을 분리하기 위한 실행 체크리스트와 테스트 절차를 관리한다.

- `android_stationary_and_field_test_checklist_20260710.md`: 연결된 Android 폰의 정지 테스트, 다른 폰의 원거리 현장 테스트, 앱 전용 로그 회수 절차
- `phone_field_test_master_checklist_20260710.md`: Web/PWA와 Android 연구 보조 경로를 구분한 통합 실행 순서와 현재 connected-phone 상태
- `web_remote_field_test_20260711.md`: 2026-07-11 Web/PWA 원거리 접속의 역사 실행 기록(현재 실행 금지)
- `android_stationary_test_report_20260710.md`: 현재 연결 폰에서 실제 수행한 ADB·설치·phone-to-backend·PostGIS 결과와 PIN 잠금 BLOCKED 항목

테스트 결과는 실행한 기기·APK hash·환경·관찰 결과·회수된 로그 경로를 함께 남겨야 한다. 체크리스트를 수행하지 않은 항목은 구현 코드가 있어도 Device PASS로 올리지 않는다.

과거 브라우저 PWA lifecycle 도구와 결과는 역사 회귀자료다. 현재 CI에서는 실행하지 않으며 Android 시험이나 출시 근거로 승격하지 않는다.

과거 Web release A→B service-worker 전환 절차도 현재 실행 대상이 아니다. 기존 결과는 당시 Web bundle의 역사자료일 뿐이다.

과거 Web artifact 생성·Web-only release evidence 절차는 차단됐다. 현재 실폰·기관·provider 증거는 향후 승인될 Android 릴리스 계약과 정식 시험계획에 따라 새 revision으로 작성한다.

### 역사 Web release gate 예시 — 실행 금지

아래 운영 gate는 `LEGACY_REFERENCE_ONLY`인 과거 계약이며 현재 CLI가 코드 78로 차단한다. 현행 Android 릴리스 검증이나 출시 근거로 실행하지 않는다. 당시 절차는 `backend/requirements.lock`과 정책의 site closure가 정확히 일치하는 전용 `BACKEND_PYTHON`을 공용 격리 bootstrap으로 실행하고, 서로 다른 field/admin 내부 토큰, 양쪽 account JSON, 별도 session secret, 두 scope의 retention apply receipt, report retention manifest, 암호화 backup/복구 drill, 기관 접수 receipt/export/manifest, 사람이 작성한 privacy receipt와 외부 제출 산출물을 모두 명시했다.

```bash
"${BACKEND_PYTHON}" -I -S -B scripts/run_walksafe_isolated_python_20260713.py \
  --repo-root . --product backend -- \
  scripts/check_walksafe_release_evidence_20260711.py \
  --profile web-release \
  --evidence <release-evidence.json> \
  --retention-receipt <field-retention.json> \
  --test-capture-retention-receipt <test-capture-retention.json> \
  --report-retention-manifest <report-retention.json> \
  --backup-manifest <backup/manifest.json> \
  --restore-drill-receipt <restore.json> \
  --agency-submission-receipt <agency-receipt.json> \
  --agency-export <agency.csv> \
  --agency-export-manifest <agency-manifest.json> \
  --submission-asset-dir <external-submission-assets> \
  --design-document-dir <external-design-documents> \
  --final-submission-dir <external-final-submission> \
  --submission-form-manifest <external-form-manifest.json> \
  --submission-privacy-receipt <human-privacy-receipt.json> \
  --submission-python <exact-8-venv/bin/python>
```

gate는 private clean clone에서 exact-8 submission runner로 제출물 37개를 재생성·승격하고 외부 산출물과 byte 단위로 비교한다. source/artifact binding이 다른 과거 증거, 예시 파일, dry-run retention, 미암호화 backup, 실제 외부 receipt ID가 없는 제출 기록, assistant-only privacy receipt는 의도적으로 FAIL한다. 공통 Android device/debug 증거는 `android-research`와 두 경로를 결합한 `full` profile에서 필수이고, gate가 검증한 서명 release APK의 실기기 smoke는 `full`에서만 추가로 필수다. `web-release`는 두 Android 증거를 요구하지 않는다.

standalone 서버는 압축 해제 루트에서 실행하고 `WALKSAFE_SOURCE_COMMIT`을 manifest의 commit으로 설정해야 한다. `/api/release-identity`는 런타임 환경값과 루트 `BUILD_ID`가 정확히 같을 때만 `ready`를 반환하며, release gate는 이 응답과 source-bound 정적 probe를 모두 확인한다.

`pwa_mobile_install_update`, `voice_report_microphone_e2e`, `web_phone_outdoor_navigation`, `web_non_metric_advisory_field`, `web_accessibility_tts_haptic`, `android_arcore_unsupported_camera_field`, `android_signed_release_physical_smoke`, `tmap_live_route_smoke`의 `evidence_ref`는 단순 메모나 영상 파일 자체가 아니라 `walksafe.external-check-receipt.v1` JSON 영수증이어야 한다. 영수증에는 check id, 사람 operator, 같은 source/artifact binding, 시작·종료 시각, check별 기기/OS/browser 또는 provider endpoint 환경, 필수 단계별 `observed_at`·`latency_ms`·판정·구체 관찰 메모, 모든 단계를 참조하는 pass criteria, check별 결과가 들어간다. 필드 집합·단계 순서·값·타입·범위는 `scripts/walksafe_external_check_receipt.py`의 check 계약과 정확히 같아야 한다. 원본 화면 녹화·로그는 영수증의 단계 메모에서 개인정보를 배제한 보관 위치나 식별자로 연결하되, 영수증 자체는 실제 관찰 후 작성한다.

```json
{
  "schema_version": "walksafe.external-check-receipt.v1",
  "check_id": "REPLACE_WITH_ONE_OF_THE_STRUCTURED_CHECK_IDS",
  "result_status": "not_observed",
  "source_commit": "REPLACE_WITH_FULL_40_HEX_GIT_COMMIT",
  "release_artifacts_sha256": "REPLACE_WITH_PRECHECK_BINDING",
  "operator": "REPLACE_WITH_HUMAN_OPERATOR",
  "started_at": null,
  "completed_at": null,
  "environment": {},
  "observations": [
    {"step_id": "REPLACE_WITH_REQUIRED_STEP", "observed_at": null, "latency_ms": null, "status": "not_observed", "note": "TODO"}
  ],
  "pass_criteria": [
    {"criterion_id": "REPLACE_WITH_REQUIRED_CRITERION", "passed": false, "observation_step_ids": []}
  ],
  "result": {}
}
```

위 블록은 형식 안내용 비통과 틀이다. `release_evidence.example.json`의 check별 `required_observations`와 `required_pass_criteria`를 모두 한 번씩 기록해야 하며, check별 환경·결과 필드는 아래와 같다.

| check | 필수 환경 | 필수 결과 |
| --- | --- | --- |
| PWA 설치·업데이트 | device, OS, browser, 배포 HTTPS URL | 설치/standalone 실행/SW 업데이트, 서로 다른 초기 commit과 현재 source commit |
| 실마이크 음성 신고 | device, OS, browser, microphone, 배포 HTTPS URL | `report_hazard` intent와 서버에서 확인한 report id |
| 실외 Web 길안내 | device, OS, browser, 배포 HTTPS URL, privacy-safe route label, GPS accuracy, weather | `tmap_pedestrian`, 2회 이상 안내, 실제 목적지 도착 |
| Web 비계량 보조 | 실폰, 후면 camera, browser, 배포 HTTPS URL, 활성 TMAP | 서로 다른 3 frame·700ms 뒤 low 좌/정면/우, metric·거리·걸음·신고·경로변경 권한 없음, 진동 없음, gate loss 뒤 `TMAP_ONLY` |
| Web 접근성 | 실폰, browser, screen reader, 배포 HTTPS URL | TTS·haptic·위험 우선순위·unavailable 상태를 모두 실기기에서 확인하고 release evidence의 `details`와 영수증을 일치시킴 |
| ARCore 미지원 Android | 실제 미지원 실폰, 같은 source commit의 결속 debug field APK, 별도 서명 release APK, CameraX, physical IMU, 활성 TMAP | field APK 설치·cold start, 최대 1 Hz·60개 이상·15분 span·gap 1~30초, TTS 또는 TalkBack, 진동 없음, 이후 TMAP 활성 `TMAP_ONLY` fallback |
| 서명 Release APK 실기기 smoke (`full` 전용) | 실제 ARCore 하드웨어 미지원 실폰, gate가 검증한 것과 동일한 서명 release APK, CameraX, 활성 TMAP | 설치 APK와 release artifact SHA-256 일치, cold start·CameraX detector·low 비계량 방향 안내·TTS/TalkBack, 거리·걸음·경로변경·신고 권한 없음, 진동 없음, gate loss 뒤 `TMAP_ONLY` |
| TMAP live smoke | `tmap_pedestrian`, 공식 HTTPS 보행자 base path와 query 없음 또는 정확한 `version=1`, production deployment | HTTP 200, route id, 좌표·안내 개수. 중복·추가 query와 `appKey` URL 기록은 금지 |

Android 미지원 필드 영수증은 같은 디렉터리 안 debug field APK와 `field_session_summary.json`을 각각 안전한 상대 `path`·정확한 `bytes`·소문자 SHA-256으로 묶는다. gate는 field APK의 실제 DEX `BuildConfig`에서 source commit·debug marker를 읽고, 고정한 `apksigner`/`apkanalyzer`로 단일 서명·WalkSafe package·SDK 범위·`debuggable=true`를 확인한 뒤 summary의 APK hash를 대조한다. 서명 release APK는 별도 release artifact로 검증한다. 두 APK는 같은 source commit에 결속되지만 서로 다른 파일이며, 이 영수증은 release APK 자체의 실기기 실행 근거가 아니다. 또한 strict camera-non-metric, 실제 ARCore 미지원 origin, 완료 session, 빈 failure 목록, raw elapsed에서 재계산한 최대 1 Hz·60개 이상·15분 span·gap 1~30초 sparse continuity와 그 뒤의 TMAP 활성 fallback을 직접 확인한다. 개인정보 제한 summary에는 record별 `recorded_at` 원문이 반복되지 않으므로 그 부분은 summary의 strict flag와 빈 failure 목록에 결속되며, 회수한 원본 앱 전용 JSONL은 별도 감사용으로 보존한다.

`full` 검증은 위 debug field 증거에 더해 `android_signed_release_physical_smoke`를 요구한다. 이 영수증은 gate가 검증한 서명 release APK SHA-256과 기기에 설치된 APK SHA-256이 정확히 같고, 디버그 강제가 아닌 `UNSUPPORTED_DEVICE_NOT_CAPABLE` 실기기에서 release binary가 CameraX fallback을 실제 실행했을 때만 통과한다. `android-research` 프로필은 서명 release가 없는 연구 실행도 허용하므로 이 추가 check를 요구하지 않는다.

구조화 영수증이 한 필드라도 없거나 한 줄 자유 텍스트만 있으면 gate는 fail-closed한다. 실제 실폰·후면 camera·TMAP·TTS/TalkBack·진동 관찰을 수행하지 않았다면 영수증을 생성하거나 `passed=true`로 바꾸면 안 된다. 예시 파일은 `passed: false`인 작성 틀이므로 외부 PASS 증거가 아니다.

이 external-check 영수증은 source/artifact와 구조를 검증하는 사람 작성 기록이며 그 자체가 사람의 암호학적 서명은 아니다. 따라서 형식이 맞는 JSON만으로 실제 관찰의 진위를 새로 증명하지는 않는다. `full` production의 reviewer-signed blocker resolution·operator attestation은 별도 최종 승인 계층이고, check별 GPG 서명은 이번 영수증 계약 범위에 포함하지 않는다.

`android-research` 또는 `full` 검증 전에는 Android SDK launcher·support closure와 system-managed Java를 SHA-256으로 승인하고 release 인증서 지문을 별도 환경 설정으로 고정한다. Java home 전체는 root 소유이며 group/world writable 경로가 없어야 한다. 인증서 지문은 검증 대상 APK가 아니라 승인된 keystore 또는 기존 기준 APK에서 대조해 얻어야 한다. 아래 inspection은 stdlib-only로 실행되어 정확히 필요한 8개 환경값을 JSON으로 출력한다.

```bash
python3 -I -S -B scripts/check_walksafe_release_evidence_20260711.py \
  --profile android-research \
  --print-android-tool-pins \
  --android-java /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
  --android-apksigner /opt/android-sdk/build-tools/36.1.0/apksigner \
  --android-apkanalyzer /opt/android-sdk/cmdline-tools/latest/bin/apkanalyzer \
  > /secure/approved-android-tool-pins.json

# 승인 JSON의 값을 변경 없이 같은 이름의 환경 변수로 설정한다.
export WALKSAFE_JAVA_BIN='<approved JSON value>'
export WALKSAFE_JAVA_SHA256='<approved JSON value>'
export WALKSAFE_APKSIGNER_BIN='<approved JSON value>'
export WALKSAFE_APKSIGNER_SHA256='<approved JSON value>'
export WALKSAFE_APKSIGNER_SUPPORT_SHA256='<approved JSON value>'
export WALKSAFE_APKANALYZER_BIN='<approved JSON value>'
export WALKSAFE_APKANALYZER_SHA256='<approved JSON value>'
export WALKSAFE_APKANALYZER_SUPPORT_SHA256='<approved JSON value>'
export WALKSAFE_ANDROID_RELEASE_SIGNER_SHA256='<승인된 인증서 SHA-256 64 hex>'
```

gate는 APK를 한 번 snapshot해 ZIP 검사와 SDK 검증에 같은 bytes를 전달한다. apksigner shell 대신 승인 support closure의 `apksigner.jar`를 고정 Java로 직접 실행하고, analyzer launcher·Java·support closure의 전후 identity를 확인한 뒤 v2/v3 서명, WalkSafe application id, version, SDK 계약, 유효 DEX, DEX 내부 source commit을 판정한다.
