# WalkSafe 작업 현황 보고서 (2026-09-25)

**장기 작업의 이전 기준:** `docs/handoff/WALKSAFE-TRANSFER-START-HERE.md`. 아래 표는 9월 25일의 국소 종료 상태이며 942행 전체의 재판정 결과가 아니다.

> 이 날짜는 다일 작업의 **종료 시점 현황**이다. 9월 17일 원문 수집, 9월 21일 942행 계획, 그 전후의 제품 보정·검토·실행 전체를 하루 작업으로 축소한 보고서가 아니다. 전체 자료 계보는 `docs/handoff/2026-09-25-walksafe-provenance-map.md`에서 확인한다.

## 판정 기준

- 이 보고서는 이 컴퓨터에서 종료하는 작업 묶음의 인계 기록이다. 다른 컴퓨터로 파일을 전송하거나 그 컴퓨터에서 재검증한 기록은 아니다.
- 21개 기능 분석과 942행 계획은 동일한 개념이 아니다. 942행 전부를 확인된 제품 결함이나 완료 대상으로 취급하지 않는다.
- `/home/ddobagi/Hanium_chat_review_2026-09-17/implementation-plan-items-20260921.csv`의 `current_status` 열을 이번 인계에서 한 번 집계한 결과는 데이터 942행, `DONE` 52, `PARTIAL` 36, 공란 854, 기타 0이다. 이 날짜형 CSV가 현행 정본인지 별도 확인하지 않았으며 이번 작업에서 CSV를 갱신하지 않았다.
- 아래 `통과`는 지정한 로컬 시험의 결과일 뿐 원문 항목 전체 `DONE`, 실DB, 운영 네트워크, 실기기 검증을 뜻하지 않는다.
- 여러 작업자가 공유 작업공간을 수정했다. 이 보고서는 전체 변경 파일 목록이나 깨끗한 작업트리 증명이 아니다. 인계 시 저장소 전체와 미커밋 작업을 보존해야 한다.

## 이번 종료 시점의 직접 결과

| 범위 | 제품/시험 파일 | 마지막 확인 | 판정 |
| --- | --- | --- | --- |
| S01-7 OTP 동일 요청 ID | `backend/app/services/accounts.py`, `backend/tests/test_accounts.py` | 실패한 fixture를 고쳐 집중 시험 5 passed (0.70s). 실제 DB/SMTP 없음. | 국소 회귀 통과, 원문 `PARTIAL` |
| S20-09 신고 이미지 키 세대 잠금 | `backend/app/services/report_image_keys.py`, `backend/tests/test_report_image_keyring_locking.py`, `backend/tests/test_report_image_key_rotation_lifecycle.py` | 기존 가짜 세션의 공유/배타 잠금 단언을 맞춘 뒤 집중 시험 8 passed (0.15s). 실제 PostgreSQL 2세션 경쟁 없음. | 국소 회귀 통과, 원문 `PARTIAL` |
| 원시 청크 키 세대 | `backend/app/services/raw_collection_storage.py`, `backend/tests/test_raw_collection_keyring.py` | 슬롯 선택 전 동일 DB 세션의 키 세대 동기화. 오프라인 직접 시험 2 passed; 실제 DB 경쟁 미실행. | `PARTIAL` |
| S20-11 신고 저장소 런타임 복구 | `backend/app/services/report_storage.py`, `backend/app/services/report_storage_recovery.py`, `backend/app/api/health.py`, `backend/app/main.py`, `backend/tests/test_report_storage_runtime_recovery.py`, `backend/tests/test_health_readiness.py` | 신규 오프라인 3 passed. 최초 인접 시험 5 failed. 이번 보정 뒤 첫 readiness 시험도 `report_storage_inventory_invalid` 예상 대신 `report_storage_recovery_unavailable`로 실패하여 즉시 중단. | **HOLD: 현재 통합 회귀 실패** |
| Gateway Backend 계정 세션 현재성 게이트 | `apps/android-gateway/src/routes.ts` | `npm run typecheck` 통과, 빌드 통과. `backend-session-current-route` 16건 중 14 passed, 2 failed (`201` 예상, `422` 실제). 독립 검토에서 요청 취소가 상위 현재성 조회로 전파되지 않는 자원 사용 위험(P2) 지적. | **HOLD: 원인/취소 경계 미해결** |
| S18-18 관리자 로컬 로그아웃 | `apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java`, `apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminLocalOperationCloseTest.java` | 선택 클래스 `--rerun-tasks` 실행, exit 0, `BUILD SUCCESSFUL`, 23 tasks executed. 독립 검토는 확정 결함 0이나 컨트롤러 본문 일부가 잘려 전체 in-flight 경로 판단 불가. | 국소 시험 통과, 전체 검토 `PARTIAL` |
| S06-10 음성 후보 안내 | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt`, 해당 시험 및 `RouteNavigatorFacingGuidanceTest.kt` | 두 선택 Android JVM 클래스 각각 exit 0. 관련 통합 동의/음성 회귀 선택 클래스 6개도 별도 로컬 실행 통과. 기기 음성 출력 미확인. | 국소 JVM 통과, 원문 `PARTIAL` |

## 이전 변경 중 명시적 HOLD

- S15-24 자동 보고 쿨다운: 독립 검토에서 전진 시계 점프 P1 등 발견. `assembleDebug` 성공은 동작 통과가 아니며 제품/시험 보류.
- S11-2 깊이 샘플링: 행별 열 인덱스 불일치 P1. 조립 성공, 직접 시험은 당시 Kotlin 시험 컴파일 메모리 실패로 미실행. 제품/시험 보류.
- S13-40 피드백 기록 pruning, S09-19 걸음 센서 리스너, S19-40 Gateway 삭제 outbox, S16-25 Backend debug-log SQLite 전환: 이전 검토·시험 문제를 해결하지 않은 변경으로 보류. 상세 근거는 당일 작업일지와 원 작업 대화에서 이어받는다.
- S02-18 음성 Foreground Service 시작 예외의 원문 설명은 현재 서비스 본문과 일부 다르다. 알림 Stop 뒤 stale 요청 플래그를 단순 해제하면 자동 재시작 위험이 있어 제품 수정하지 않았다.
- S01-28 field-walk 원장의 감사 기록 8,192건 상한은 자동 아카이브가 필요한 설계 과제이다. 제품 변경/시험은 없고 별도 저장·키 회전 계약이 필요하다.

## 이전 국소 근거와 한계

- S20-28-A/B TMAP 거리 보정, S20-10 commit 후 refresh 분류, S20-39 SQL 집계, Android 통합 동의와 음성 poll 소유권 등에는 각각 국소 시험/독립 검토 근거가 있으나 여기서 원문 전체 `DONE`으로 승격하지 않는다.
- Gateway FIFO `O_NONBLOCK` 보정의 직접 시험 3 passed 기록은 Linux FIFO/no-writer·합성 키링 범위이다. 운영 키 권한/회전 전체는 아니다.
- Android 테스트는 로컬 JVM 및 Gradle 범위다. APK 설치, 마이크, GNSS, TTS, 실기기 보행 안전성 결과가 아니다.
- 최신 실행 결과는 작업자 반환과 Root 실행 결과를 구분해 이 보고서에 요약했다. 이번 문서 작성 뒤 파일·시험 결과를 다시 읽어 검증하지 않았다.

## 근거 위치

- 원문 및 날짜형 계획: `/home/ddobagi/Hanium_chat_review_2026-09-17/`
- 증거 자료 디렉터리: `/home/ddobagi/Hanium_chat_review_2026-09-17/implementation-evidence-20260919-resumed/`
- 당일 작업 기록: `/home/ddobagi/Hanium_Dreamup/daylog/2026-09-25.md`
- 재개 절차: `docs/handoff/2026-09-25-walksafe-transfer-guide.md`
- 항목별 다음 작업: `docs/handoff/2026-09-25-walksafe-work-queue.md`
- 장기 이력·원문·계획·증거 지도: `docs/handoff/2026-09-25-walksafe-provenance-map.md`
