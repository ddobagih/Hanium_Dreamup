# Product Roadmap

## 운영 모드

- 기본 모드: `android-native-validation`
- 이유: 현재 핵심은 Web/PWA 기능을 더 늘리는 것이 아니라 Android native ARCore APK에서 **bbox, depth, TFLite detector, `N보` 안내의 실기기 정합성**을 확인하는 것이다.
- Web/PWA, STT, TMAP, admin/backend는 보조 lane으로 유지하되 Android coordinate/depth gate 전에는 P0 기능 추가로 올리지 않는다.
- 배포, 외부 공개, 운영 DB, secret, 비용 발생 API, 공공기관 제출은 별도 승인 전 실행하지 않는다.

## 단계별 목표

| 단계 | 목표 | 대표 기능 | 완료 기준 | 상태 |
|---|---|---|---|---|
| M0 기준선 | Android native APK와 backend/admin/PWA/voice 역할 분리 | APK path/hash, runtime config, overlay, evidence 등급 정리 | source-of-truth 문서가 Android 주경로와 unified-primary 전환을 반영하고 stale PWA 중심 표현 제거 | done_static |
| M1 Android core | 실기기에서 detector bbox와 ARCore depth가 같은 객체를 가리키는지 검증 | bbox overlay, depth sample median, detector age, coordinate mapper | 화면 중앙/좌우/상하 객체에서 bbox 정렬, depth가 가까움/멀어짐에 따라 일관 변화 | 다음 P0 |
| M2 제품 연결 | gate 통과 후 TTS/haptic/report upload 최소 연결 | `UserFacingDepth.message`, haptic, `/reports/v2`, GPS/heading metadata | report-only damage 정책 유지, 일반 객체는 위험 context에서만 경고, Android source metadata 기록 | code_connected, Device/DB 검증 필요 |
| M3 운영/제출 | 외부에 보여도 과대해석 없는 demo/release evidence | 최종 보고서, 테스트 보고서, export sample, demo/mock release | Device/Release/Model/GIS evidence가 분리되고 승인된 범위만 공개 | blocked_C/후순위 |

## 이번 주 우선순위

1. Android APK 설치/관찰 기준 고정
   - 기준 APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
   - SHA-256: `1f5b2020053d755e6f52054453c82bd2eb45d3bd6ade3740a8d9bfa2f35967c9`
2. Overlay 좌표 정합 1차 판정
   - 사람/점자블록/일반 물체를 중앙·좌우·상하에 두고 bbox가 실제 객체와 맞는지 기록한다.
3. Depth sampling 정합 확인
   - sample count/ratio/median이 같은 객체 이동에 따라 일관되게 변하는지 본다.
4. Coordinate mapper 필요 여부 결정
   - overlay가 밀리면 ARCore image/display/depth transform mapper를 구현한다.
5. 문서 source-of-truth 정리
   - `docs/README.md`의 canonical map을 기준으로 stale 계획/실행 로그를 legacy/superseded로 분리한다.

## 다음 기능 후보

| 우선순위 | 기능 | 왜 지금 필요한가 | 예상 범위 | 막힘/주의 |
|---|---|---|---|---|
| P0 | Android overlay 좌표 정합 | bbox가 실제 객체 위에 맞아야 depth와 경고가 의미 있음 | 실기기 관찰 + 필요 시 mapper 구현 | Device 관찰 필요 |
| P0 | ARCore depth sample 검증 | `1보`/거리 안내가 같은 객체 기준인지 확인 필요 | sample count/median, 가까움/멀어짐 테스트 | 실측 거리 필요 |
| P0 | detector age/stale guard | 오래된 detection으로 depth/TTS를 만들면 위험 | age limit, UI/log, unit/static check | done_static, Device 확인 필요 |
| P1 | metadata-only capture log | 이미지 저장 없이 반복 가능한 bbox/depth 검토가 필요 | frameTs, detectorTs, class, bbox, depth median ring buffer | done_static, 서버 전송은 debug/local only |
| P1 | Android/backend threshold 정리 | Android app과 backend smoke 수치를 혼동하면 안 됨 | decision note, config table, warning 설명 | full metric rerun은 dataset 복구 필요 |
| P1 | TTS/haptic 실기기 확인 | 코드는 Device Gate 뒤에 연결됐고 사용자 경험 검증이 남았다 | Android TextToSpeech/haptic rate limit + Device 청취/진동 | field evidence 필요 |
| P1 | `/reports/v2` upload E2E | 코드는 damage-only/GPS 필수로 연결됐고 DB no-skip 검증이 남았다 | Android upload, backend 저장, Admin Android source 확인 | PostGIS/실기기 필요 |
| P2 | Voice/STT Android 연결 | hands-free 제어 | local/PWA prototype 재사용 검토 | 후순위 |
| P2 | TMAP/navigation | 길안내와 위험 안내 결합 | backend proxy 재사용 | 후순위, key는 client에 넣지 않음 |

## 검증/안정화 후보

| 항목 | 필요한 환경 | 완료로 인정되는 근거 | 대체 가능 여부 |
|---|---|---|---|
| Android Gradle test/build | local JDK/Gradle | `./gradlew test`, `./gradlew assembleDebug` BUILD SUCCESSFUL | 가능 |
| Android overlay/depth field | ARCore 실기기, 카메라 권한 | 화면/로그/관찰 기록, APK hash | headless 대체 불가 |
| Static detector threshold | reviewed dataset 또는 saved reports | dataset/hash/threshold별 CSV/summary | saved prediction 기반 부분 가능 |
| Backend reports/export | disposable DB/backend | no-skip tests/API smoke/export artifact | ASGI/no-DB는 부분 근거 |
| PWA/voice | browser/phone mic/TTS | mic transcript/intent/UI action/청취 기록 | local sample은 E2E 대체 불가 |
| Release | domain/secret/storage/rollback 승인 | 승인된 배포 evidence | 승인 전 금지 |

## release 모드 전환 조건

아래 조건이 동시에 만족되기 전까지는 release가 아니라 validation/demo다.

1. Android Device에서 bbox overlay와 실제 객체가 정렬된 evidence가 있다.
2. ARCore depth sample/median이 실측 거리 또는 반복 관찰과 일관된다.
3. detector age/stale guard가 적용되어 오래된 bbox를 제품 경고로 쓰지 않는다.
4. `damaged_tactile_block` report-only 정책과 일반 객체 위험 경고 정책이 Android에서 유지된다.
5. `/reports/v2` upload와 `/admin` 조회가 disposable DB 또는 승인된 환경에서 확인된다.
6. 배포 도메인, HTTPS, secret, storage, 외부 API 사용 여부가 사용자 승인으로 결정된다.

## 근거 문서

| 근거 파일 | 반영한 내용 |
|---|---|
| `docs/current_status.md` | 현재 Android/native/backend/model/voice 상태 |
| `apps/android/README.md` | APK/설치/config/overlay |
| `docs/android/arcore_depth_estimation_architecture.md` | 좌표/depth 구조와 risk |
| `docs/android/android_device_overlay_depth_checklist_20260601.md` | Device overlay/depth 관찰 기준 |
| `docs/evidence/final_report_index.md` | evidence 색인과 완료 주장 제한 |
| `docs/README.md` | canonical/legacy 문서 구분 |
