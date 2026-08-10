# Product Roadmap

> 상태: `SUPERSEDED_PRODUCT_BOUNDARY_SNAPSHOT`. 아래 내용은 2026-07-11 Web/PWA 전제의 역사 자료다. 현재 실행 순서는 `docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r001.json`, 현재 비전은 `product/vision.md`를 사용한다.

## 운영 모드

- 기본 모드: `web-pwa-release-validation`
- 이유: 주 사용자 앱은 Web/PWA로 확정됐고 핵심 사용자 흐름은 연결돼 있다. 현재 우선순위는 fake 기본 설정을 실제 모델로 바꾸고 모바일 브라우저와 운영 환경에서 검증하는 것이다.
- Android native는 ARCore depth와 기기 내 TFLite의 보조 연구 lane으로 유지한다.
- 기관 API 자동 제출은 구현하지 않는다. 관리자가 검수·필터 후 CSV를 내려받아 외부 채널에 수동 신고한다.

## 단계별 목표

| 단계 | 목표 | 대표 기능 | 완료 기준 | 상태 |
|---|---|---|---|---|
| M-1 범위 정합 | 제품 플랫폼과 기관 신고 방식 확정 | Web/PWA primary, Android 보조, 관리자 CSV 수동 신고 | source/제품/제출 문서가 같은 범위를 사용 | done_20260710 |
| M0 기준선 | Web/backend/admin/Android 보조 역할과 evidence 등급 정리 | current audit, 폴더 README, 분류 manifest | source-of-truth 문서와 코드 책임 경계가 일치 | done_static |
| M1 Model/Backend core | 배포 후보 모델 품질과 backend real provider 검증 | corrupt/weak label 정리, unified checkpoint, real provider, latency | 독립 model gate와 ASGI/browser source·latency 근거 | 다음 P0 |
| M2 Web 제품 연결 | 모바일 Web의 탐지·TTS/진동·음성 길안내·신고 검증 | PWA/HTTPS, camera/GPS/mic, `/reports/v2`, route/retry | mobile field E2E와 fail-closed 정책 | code_connected, Field/Release 필요 |
| M3 운영/제출 | 관리자 검수·CSV 수동 신고와 운영 보안 검증 | PostGIS, auth/RBAC/audit, export sample, runbook | no-skip DB와 승인된 release evidence | blocked_env/후순위 |

## 이번 주 우선순위

1. Web release 설정 기준 고정
   - `NEXT_PUBLIC_DETECTOR_MODE=server-v2`, backend `yolo`/`real`, PWA enable, HTTPS/domain 값을 release manifest로 기록한다.
   - 운영 환경에서 fake fallback 없이 실패 상태를 사용자에게 안전하게 표시한다.
2. Model quality gate 정리
   - corrupt image/label과 `curb_step`/`uneven_sidewalk` 라벨 정책을 정리하고 독립 평가를 만든다.
3. 모바일 Web 현장 흐름 검증
   - camera/GPS/heading, sampled detector, browser TTS/진동, 음성 목적지 변경·취소·다음 안내 질의·길안내·재탐색·신고를 실폰 브라우저에서 기록한다.
   - 위험 > 명령 상호작용 > 길안내 중재, frozen/stale 탐지 불가 상태와 gate 기반 점자블록 local steering·TMAP 복귀를 함께 확인한다.
4. PostGIS/Admin 운영 흐름 검증
   - report→중복→검수→필터 CSV→외부 채널 수동 신고 절차를 auth/RBAC 포함 환경에서 검증한다.
5. Android 보조 연구 검증
   - 필요 시 bbox/depth/TFLite/FPS를 Web 제품 evidence와 분리해 기록한다.
6. 문서 source-of-truth 정리
   - `docs/README.md`의 canonical map을 기준으로 stale 계획/실행 로그를 legacy/superseded로 분리한다.

## 다음 기능 후보

| 우선순위 | 기능 | 왜 지금 필요한가 | 예상 범위 | 막힘/주의 |
|---|---|---|---|---|
| P0 | Web real detector 연결 | fake가 아닌 모델 결과로 위험 판단해야 함 | backend model path/hash, `server-v2`, fail-closed UI | 품질 승인 checkpoint 필요 |
| P0 | PWA HTTPS/mobile field | 주 앱의 install·센서·음성·신고를 실제 환경에서 확인 | domain/HTTPS, 실폰 browser E2E | 배포 환경 필요 |
| P0 | 13-class 모델 품질 복구 | 현재 PT는 단차/불균일 보도 recall이 낮고 corrupt data가 있음 | label/data 정리, 독립 eval, TFLite export | final checkpoint 미확정 |
| P0 | Admin CSV 운영 보안 | 검수되지 않은 신고·무권한 다운로드를 막아야 함 | PostGIS no-skip, auth/RBAC/audit, CSV 수동 신고 runbook | 운영 DB 필요 |
| P0 | detector age/stale guard | 오래된 detection으로 TTS/신고를 만들면 위험 | age limit, UI/log, unit/static check | done_static, mobile field 필요 |
| P1 | metadata-only capture log | 이미지 저장 없이 반복 가능한 bbox/depth 검토가 필요 | frameTs, detectorTs, class, bbox, depth median ring buffer | done_static, 서버 전송은 debug/local only |
| P1 | Web/backend threshold 정리 | browser source와 backend model 수치를 혼동하면 안 됨 | release config, model/hash/threshold 표 | 독립 metric 필요 |
| P1 | TTS/haptic 실기기 확인 | Web 정책 코드와 사용자 체감 검증이 남았다 | 모바일 browser 청취/진동, rate limit | field evidence 필요 |
| P1 | `/reports/v2` upload E2E | Web 이미지·위치·metadata 신고와 DB 검증이 남았다 | Web upload, backend 저장, Admin source 확인 | PostGIS/실기기 필요 |
| P1 | TMAP/navigation field | TMAP-only 전역 길안내, 음성 조작과 위험 안내를 현장 검증 | backend proxy, route 상태기, 3 frame·700ms/confidence/freshness/GPS/heading/corridor gate 기반 tactile local steering·TMAP 복귀 | key는 client에 넣지 않으며 전체 점자블록 경로망·안전 보장을 주장하지 않음 |
| P2 | Android ARCore/TFLite 연구 | depth/온디바이스 추론 가능성 검증 | APK, overlay/depth/FPS evidence | Web release와 분리 |

## 검증/안정화 후보

| 항목 | 필요한 환경 | 완료로 인정되는 근거 | 대체 가능 여부 |
|---|---|---|---|
| Web lint/type/build | Node/npm | lint, typecheck, production build | 가능 |
| Web mobile field | HTTPS, 실폰 브라우저, camera/GPS/mic 권한 | 탐지·음성·길안내·신고 화면/로그 | headless 대체 불가 |
| Static detector threshold | reviewed dataset 또는 saved reports | dataset/hash/threshold별 CSV/summary | saved prediction 기반 부분 가능 |
| Backend reports/export | disposable DB/backend | no-skip tests/API smoke/export artifact | ASGI/no-DB는 부분 근거 |
| Android 보조 연구 | JDK/Gradle, ARCore 실기기 | APK hash, overlay/depth/FPS 관찰 | Web field 대체 불가 |
| Release | domain/secret/storage/rollback 승인 | 승인된 배포 evidence | 승인 전 금지 |

## release 모드 전환 조건

아래 조건이 동시에 만족되기 전까지는 release가 아니라 validation/demo다.

1. Web/PWA가 HTTPS/domain에 배포되고 install과 권한 실패 경로가 확인된다.
2. backend real detector와 품질 승인 모델의 path/hash/threshold가 release 설정에 고정된다.
3. 모바일 브라우저에서 camera/GPS/mic/TTS/진동·길안내·신고 E2E evidence가 있다.
4. detector age/stale guard와 일반 객체 위험 경고·손상 신고 정책이 Web에서 유지된다.
5. `/reports/v2`→관리자 검수·필터→CSV 다운로드가 PostGIS no-skip와 auth/RBAC 환경에서 확인된다.
6. 관리자가 CSV를 기관 외부 채널에 수동 신고하는 runbook과 개인정보 처리 기준이 있다.
7. 배포할 unified 모델이 독립 평가와 backend latency/FPS gate를 통과한다.
8. rollback, monitoring, storage와 secret 관리가 release evidence에 기록된다.

## 근거 문서

| 근거 파일 | 반영한 내용 |
|---|---|
| `docs/status/current_status.md` | 현재 Web/PWA/backend/admin/Android 보조/model/voice 상태 |
| `apps/web/README.md` | Web 주 앱 구조, 실행과 release gap |
| `apps/android/README.md` | 보조 연구 APK/설치/config/overlay |
| `docs/android/arcore_depth_estimation_architecture.md` | 좌표/depth 구조와 risk |
| `docs/android/android_device_overlay_depth_checklist_20260601.md` | Device overlay/depth 관찰 기준 |
| `docs/evidence/final_report_index.md` | evidence 색인과 완료 주장 제한 |
| `docs/README.md` | canonical/legacy 문서 구분 |
