# WalkSafe 소프트웨어 통합 보고서

> 포함 산출물: DEV-21  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: WalkSafe 기능 정책 1.0.0  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

승인 설계에 맞춰 Android·서버·모델·외부 서비스를 실제로 통합했고 그 결과를 증명했는가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


## 현재 결론

**통합 완료를 주장하지 않습니다.** 저장소에는 Android·백엔드·모델·음성·Web 후보와 과거 시험 기록이 있으나, 정책 기준선 1.0.0 이후 이름 붙인 build에 대해 54개 기능 전체를 재검증한 통합 실행은 없습니다.

| 항목 | 현재 상태 | 완료에 필요한 증거 |
|---|---|---|
| Android 사용자 앱 ↔ 백엔드 | 재검증 필요 | 인증·TMAP·신고·삭제 API 계약시험과 실기기 E2E |
| Android 관리자 앱 ↔ 백엔드 | 구현 경계 확인 필요 | 별도 앱 build, RBAC, 검수·기관 전달 흐름 |
| Android ↔ TFLite 모델 | 후보 존재 | 출시 모델 파일 지문(hash), 변환 동등성, 기기별 성능 |
| Android ↔ TMAP | 후보 존재 | schema·장애·쿼터·이탈·사용자 재탐색 판단 시험 |
| 원본 수집 ↔ 저장·삭제 | 전체 재검증 필요 | 동의, 암호화, 전송 확인, 기간 만료, 권리요청 E2E |
| 운영·복구 | 미실행 | 단일 관리자 복구훈련과 backup/restore 증거 |

## 통합 실행 때 반드시 기록할 값

- source commit·working tree 상태
- Android 사용자·관리자 APK ID와 SHA-256
- 서버 실행 이미지·빌드, DB 구조 변경(migration), OpenAPI 파일 지문(SHA-256)
- TFLite 모델·설정·threshold SHA-256
- 기기·OS·권한·네트워크·TMAP 환경
- 실행 시작·종료 시각, 시험 수행자, 원자료 위치·파일 지문(hash)
- 실패·skip·부분 성공·결함·잔여위험

## 아직 실행하지 않은 필수 검증

- GATE-PHONE-QUEUE-BYTE-LIMIT: 휴대전화 대기자료의 실제 용량 한도
- GATE-SERVER-CAPACITY-STATE-CONTRACT: 서버 용량상태를 휴대전화에 전달하는 규칙
- GATE-RAW-COLLECTION-RELEASE-REVIEW: 무가림 원본 수집의 출시 전 독립 검토
- GATE-CLOUD-COST-MEASUREMENT: 실제 클라우드 저장비 측정
- GATE-SINGLE-ADMIN-RECOVERY-DRILL: 관리자 휴대전화 분실 복구훈련

## 승인 경계

- 통합 상태: `NOT_RUN_FOR_APPROVED_BASELINE`
- 구현 완료: 주장하지 않음
- 시험 완료: 주장하지 않음
- 출시 상태: `NOT_ELIGIBLE`

## 이번 버전 변경점

- DEV-21 보고서 틀을 처음 개설했다. 실제 통합 실행 뒤 새 build별 instance로 결과를 추가해야 한다.
