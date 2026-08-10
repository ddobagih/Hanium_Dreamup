# WalkSafe 승인 정책 대비 현행 구현 Gap 분석

- 보고서: `WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-001` v0.1.0
- 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
- 구현 commit: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`
- 결론: **출시 부적격(NOT_ELIGIBLE)** — 기준선이나 구현을 변경하지 않은 진단 보고서

## 한눈에 보는 결과

68개 기준을 모두 비교했습니다. 구현·정식검증까지 완료된 항목은 0개입니다.

| 판정 | 개수 | 뜻 |
|---|---:|---|
| 구현·정식검증 완료 (`IMPLEMENTED`) | 0 | 구현·정식검증 완료 |
| 일부 구현 (`PARTIAL`) | 16 | 일부 구현 |
| 핵심 구현 없음 (`MISSING`) | 20 | 핵심 구현 없음 |
| 정책과 충돌 (`CONFLICTING`) | 23 | 정책과 충돌 |
| 정식 증거 없음 (`EVIDENCE_MISSING`) | 4 | 정식 증거 없음 |
| 미실행 출시 확인 (`BLOCKED`) | 5 | 미실행 출시 확인 |

## 먼저 바로잡아야 할 핵심

- **CF-01 공식 문서의 정식 제품 경계가 승인안과 반대** — FP-007, FP-009
- **CF-02 별도 Android 관리자 앱·추가 본인확인 없음** — FP-008, FP-047, NPC-SINGLE-ADMIN-RECOVERY
- **CF-03 가입·통합동의·안전한 장기 세션 없음** — FP-010, FP-011, FP-013
- **CF-04 복귀 즉시 보행 기능 재개** — FP-017, FP-018
- **CF-05 도착 자동 확정·이탈 자동 재탐색** — FP-022, FP-023, NPC-NAVIGATION-ROUTE-DIRECTION
- **CF-06 자동신고 후보를 보행 중 즉시 인터넷 전송** — FP-031, FP-035, NPC-AUTO-REPORT
- **CF-07 승인된 원본 수집·보존·삭제 경로 없음** — FP-034, FP-036, NPC-RAW-ORIGINAL-COLLECTION
- **CF-08 보호 서버 후보는 있으나 계정 인증·중앙 역할별 권한·용량 계약이 미완성** — FP-040, FP-041, FP-047, NPC-SERVER-CAPACITY-STATE-SYNC
- **CF-09 정식 시험 279개 전부 미실행** — FP-049, FP-050
- **CF-10 5개 출시 확인 관문 전부 미실행** — GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL

## 기능·정책별 판정

| ID | 기능·정책 | 판정 | 우선 | 담당 | 구현 공수 | 외부 검증 공수 | 전체 |
|---|---|---|---|---|---|---|---|
| NPC-RAW-ORIGINAL-COLLECTION | 활성 보행 중 원본 수집 | 핵심 구현 없음 (`MISSING`) | P0 | 보안·개인정보책임자 | L | M | L |
| NPC-DATA-LIFECYCLE | 보존기간과 삭제기한 | 일부 구현 (`PARTIAL`) | P0 | 보안·개인정보책임자 | L | M | L |
| NPC-SERVER-STORAGE-CAPACITY | 서버 원본·백업 용량과 저장비 | 핵심 구현 없음 (`MISSING`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| NPC-PHONE-QUEUE-CAPACITY | 휴대전화 대기자료 용량 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| NPC-AUTO-REPORT | 손상 점자블록 자동신고 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| NPC-PERMISSION-SESSION-LIFECYCLE | 권한·로그인·동의 상태 분리 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| NPC-NAVIGATION-ROUTE-DIRECTION | 경로·진행방향·보폭 책임 분리 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | M | M | M |
| NPC-SINGLE-ADMIN-RECOVERY | 한 명의 관리자와 계정 복구 | 핵심 구현 없음 (`MISSING`) | P0 | 보안·개인정보책임자 | L | M | L |
| NPC-SERVER-CAPACITY-STATE-SYNC | 서버 용량상태를 휴대전화에 전달 | 핵심 구현 없음 (`MISSING`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| FP-001 | 제품 목적과 우선순위 | 일부 구현 (`PARTIAL`) | P1 | Android 기술책임자 | M | M | M |
| FP-002 | 현재 출시 단계와 완료 판정 | 정식 증거 없음 (`EVIDENCE_MISSING`) | P0 | Android 기술책임자 | M | M | M |
| FP-003 | 단일 책임자 의사결정 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | M | M | M |
| FP-004 | 우선 사용자 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | M | L | L |
| FP-005 | 공식 사용환경과 횡단보도 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | M | L | L |
| FP-006 | 단독 보행 목표와 휴대전화 장착 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | M | L | L |
| FP-007 | 사용자용 안드로이드 전용 앱 | 정책과 충돌 (`CONFLICTING`) | P1 | Android 기술책임자 | M | M | M |
| FP-008 | 관리자용 안드로이드 앱 | 핵심 구현 없음 (`MISSING`) | P0 | 보안·개인정보책임자 | L | M | L |
| FP-009 | 지원 기기와 과거 웹 버전 | 정책과 충돌 (`CONFLICTING`) | P1 | Android 기술책임자 | M | M | M |
| FP-010 | 첫 실행과 회원가입 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| FP-011 | 장기 로그인 유지 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| FP-012 | 여러 기기 동시 로그인 | 핵심 구현 없음 (`MISSING`) | P1 | Android 기술책임자 | L | M | L |
| FP-013 | 첫 실행 통합 동의 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| FP-014 | 권한 거부·철회 시 기능별 처리 | 일부 구현 (`PARTIAL`) | P0 | Android 기술책임자 | M | M | M |
| FP-015 | 사용 중 철회·계정 삭제 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| FP-016 | 로그인 뒤 카메라 중심 무버튼 화면 | 정책과 충돌 (`CONFLICTING`) | P1 | Android 기술책임자 | M | M | M |
| FP-017 | 보행 자동 시작·다른 화면 전환 시 일시중지 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| FP-018 | 보행 상태·종료·복구 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| FP-019 | 휴대전화 내부 물체 후보 탐지 | 정책과 충돌 (`CONFLICTING`) | P0 | AI·Android 기술책임자 | L | L | L |
| FP-020 | 위험 등급·우선순위·행동 안내 | 정책과 충돌 (`CONFLICTING`) | P0 | AI·Android 기술책임자 | M | L | L |
| FP-021 | 탐지 대상 종류·카메라 품질·거리 근거 | 정책과 충돌 (`CONFLICTING`) | P0 | AI·Android 기술책임자 | L | L | L |
| FP-022 | 티맵 목적지 검색과 큰 경로 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | L | L |
| FP-023 | 경로 이탈·재탐색·티맵 장애 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | L | L |
| FP-024 | 점자블록 휴대전화 내부 경로 보조와 손상 처리 | 정식 증거 없음 (`EVIDENCE_MISSING`) | P1 | Android 기술책임자 | M | L | L |
| FP-025 | 길라잡이 호출어와 휴대전화 내부 음성인식 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | L | L |
| FP-026 | 음성 명령·확인·중재 | 일부 구현 (`PARTIAL`) | P1 | Android 기술책임자 | M | L | L |
| FP-027 | 휴대전화 내부 음성안내·진동 대체 안내 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | M | L | L |
| FP-028 | 안드로이드 화면읽기 지원 범위 | 일부 구현 (`PARTIAL`) | P0 | Android 기술책임자 | L | L | L |
| FP-029 | 거부·장애 확인창의 접근 가능한 조작 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | L | L |
| FP-030 | 화면 미확인 사용과 시각 접근성 | 일부 구현 (`PARTIAL`) | P0 | Android 기술책임자 | L | L | L |
| FP-031 | 손상 점자블록 자동신고 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| FP-032 | 신고 중복·재시도·처리 단계 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| FP-033 | 관리자 검수·기관 전달·사용자 피드백 | 일부 구현 (`PARTIAL`) | P1 | Android 기술책임자 | L | M | L |
| FP-034 | 수집할 사용자 활동자료 목록 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| FP-035 | 보행 중 휴대전화 저장·정지 시 서버 전송 | 정책과 충돌 (`CONFLICTING`) | P0 | Android 기술책임자 | L | M | L |
| FP-036 | 서버 확인 뒤 휴대전화 삭제·서버 보존 | 핵심 구현 없음 (`MISSING`) | P0 | Android 기술책임자 | L | M | L |
| FP-037 | 현재 모델의 사용 위치 | 정책과 충돌 (`CONFLICTING`) | P0 | AI·Android 기술책임자 | M | L | L |
| FP-038 | 서버 재학습·학습자료 안전검사·독립평가 | 일부 구현 (`PARTIAL`) | P0 | AI·Android 기술책임자 | L | L | L |
| FP-039 | 모델 등록·교체·이전 정상 모델 복구 | 정책과 충돌 (`CONFLICTING`) | P0 | AI·Android 기술책임자 | L | L | L |
| FP-040 | 휴대전화·서버 역할과 하나의 서버 출입구 | 일부 구현 (`PARTIAL`) | P0 | 백엔드·운영 기술책임자 | M | M | M |
| FP-041 | 계정·공간 데이터베이스와 대용량 원본 저장소 | 일부 구현 (`PARTIAL`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| FP-042 | 외부 서비스 비밀키·대기자료·중복방지 | 일부 구현 (`PARTIAL`) | P0 | 백엔드·운영 기술책임자 | M | M | M |
| FP-043 | 장애가 계속될 때 멈출 기능 범위 | 정책과 충돌 (`CONFLICTING`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| FP-044 | 인터넷이 없거나 일부 기능만 쓸 때의 제한 | 정책과 충돌 (`CONFLICTING`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| FP-045 | 저장공간·배터리·발열·복구 | 핵심 구현 없음 (`MISSING`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| FP-046 | 개인정보 수집·보존·사용자 권리 | 핵심 구현 없음 (`MISSING`) | P0 | 보안·개인정보책임자 | L | L | L |
| FP-047 | 사용자·관리자 로그인과 권한 분리 | 정책과 충돌 (`CONFLICTING`) | P0 | 보안·개인정보책임자 | L | M | L |
| FP-048 | 암호화·접속정보·보안사고 | 일부 구현 (`PARTIAL`) | P0 | 보안·개인정보책임자 | L | L | L |
| FP-049 | 출시 전에 반드시 통과할 기능·안전·접근성 시험 | 정식 증거 없음 (`EVIDENCE_MISSING`) | P0 | QA책임자 | L | L | L |
| FP-050 | 휴대전화 전체 성능·현장 사용자 시험 | 정식 증거 없음 (`EVIDENCE_MISSING`) | P0 | QA책임자 | L | L | L |
| FP-051 | 앱스토어 배포·버전 일치·업데이트·이전 정상판 복구 | 일부 구현 (`PARTIAL`) | P0 | 백엔드·운영 기술책임자 | L | L | L |
| FP-052 | 운영 상태 확인·알림·단일 관리자 지원 | 일부 구현 (`PARTIAL`) | P1 | 백엔드·운영 기술책임자 | M | M | M |
| FP-053 | 백업·복원·비용·용량 운영 | 일부 구현 (`PARTIAL`) | P0 | 백엔드·운영 기술책임자 | L | M | L |
| FP-054 | 유지보수·안전한 배포·서비스 종료 | 일부 구현 (`PARTIAL`) | P1 | 백엔드·운영 기술책임자 | L | M | L |
| GATE-PHONE-QUEUE-BYTE-LIMIT | 휴대전화 대기자료의 실제 용량 한도 | 미실행 출시 확인 (`BLOCKED`) | P0 | QA책임자 | 해당 없음 | L | L |
| GATE-SERVER-CAPACITY-STATE-CONTRACT | 서버 용량상태를 휴대전화에 전달하는 규칙 | 미실행 출시 확인 (`BLOCKED`) | P0 | QA책임자 | 해당 없음 | L | L |
| GATE-RAW-COLLECTION-RELEASE-REVIEW | 무가림 원본 수집의 출시 전 독립 검토 | 미실행 출시 확인 (`BLOCKED`) | P0 | QA책임자 | 해당 없음 | L | L |
| GATE-CLOUD-COST-MEASUREMENT | 실제 클라우드 저장비 측정 | 미실행 출시 확인 (`BLOCKED`) | P0 | QA책임자 | 해당 없음 | L | L |
| GATE-SINGLE-ADMIN-RECOVERY-DRILL | 관리자 휴대전화 분실 복구훈련 | 미실행 출시 확인 (`BLOCKED`) | P0 | QA책임자 | 해당 없음 | L | L |

## 수정 작업 묶음과 순서

EPIC-01. **제품 경계와 Web 앱 오염 제거** (P0, 실행 단계 0, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: 없음; 순서: FP-003, FP-002, FP-007, FP-009, FP-001
EPIC-02. **안전한 보행 상태와 권한** (P0, 실행 단계 1, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-01; 순서: FP-017, FP-018, NPC-PERMISSION-SESSION-LIFECYCLE, FP-004, FP-005, FP-006, FP-010, FP-011, FP-013, FP-015, FP-014, FP-016, FP-012
EPIC-03. **계정·관리자 앱·보안** (P0, 실행 단계 1, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-01; 순서: FP-047, FP-008, FP-046, NPC-SINGLE-ADMIN-RECOVERY, FP-048
EPIC-04. **경로·도착·이탈 사용자 결정 흐름** (P0, 실행 단계 2, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-02; 순서: FP-022, FP-023, NPC-NAVIGATION-ROUTE-DIRECTION, FP-024
EPIC-05. **객체탐지·위험안내 안전성** (P0, 실행 단계 2, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-02; 순서: FP-019, FP-020, FP-021
EPIC-06. **음성·진동·접근성** (P0, 실행 단계 2, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-02; 순서: FP-025, FP-027, FP-029, FP-028, FP-030, FP-026
EPIC-07. **원본 수집·보존·삭제** (P0, 실행 단계 2, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-02, EPIC-03; 순서: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE
EPIC-08. **자동신고 암호화 전송 대기함과 전송** (P0, 실행 단계 3, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-02, EPIC-03, EPIC-07; 순서: FP-031, FP-032, FP-035, NPC-AUTO-REPORT, FP-034, FP-036, NPC-PHONE-QUEUE-CAPACITY, FP-033
EPIC-10. **AI 모델 수명주기** (P0, 실행 단계 3, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-05, EPIC-07; 순서: FP-037, FP-039, FP-038
EPIC-09. **서버 저장·용량·장애대응** (P0, 실행 단계 4, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-03, EPIC-07, EPIC-08; 순서: FP-043, FP-044, FP-045, NPC-SERVER-CAPACITY-STATE-SYNC, NPC-SERVER-STORAGE-CAPACITY, FP-040, FP-041, FP-042
EPIC-11. **Android 릴리스·운영·복구 구현 준비** (P0, 실행 단계 5, 현재 미착수 `PLANNED`, 목표: 구현 준비) — 선행: EPIC-09, EPIC-10; 순서: FP-051, FP-053, FP-052, FP-054
EPIC-12. **정식 시험·현장 검증·5개 게이트** (P0, 실행 단계 6, 현재 미착수 `PLANNED`, 목표: 정식 검증 완료) — 선행: EPIC-04, EPIC-05, EPIC-06, EPIC-08, EPIC-09, EPIC-10, EPIC-11; 순서: FP-049, FP-050, GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL

## 검증 경계

- Android 내부 단위시험 326건과 선택한 서버·모델 단위시험 171건은 통과했지만 정식 승인 시험이 아닙니다.
- 승인된 279개 시험과 5개 출시 확인 관문은 모두 미실행(`NOT_RUN`)입니다.
- 이 보고서는 정책·산출물 기준선, 구현 코드, 출시 상태를 변경하지 않았습니다.

보고서 내용 지문: `1edb1254cb13110e4ba273a94a8f06f2ebcb78b36fdf9760519ae21be8b4ef98`
백로그 내용 지문: `c6a12e54661def5144af49c8ada49e69d7a036d7c5e52fea43e44c75f94fa93e`
