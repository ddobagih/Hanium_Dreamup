# WalkSafe FP-035 정책 정정 후보

> 후보 ID: `WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001`  
> 상태: **미승인·미효력**  
> JSON SHA-256: `7298e024cbb88e8fda5e27dfd9e97f7ebf23250a7adf4ea6185c406adb4ed742`

## 무엇을 바로잡는가

기존 답변에서 ‘데이터’는 수집 동의가 아니라 **이동통신망 전송 선택**을 뜻했습니다. 따라서 Wi-Fi만 가능한 것으로 읽히는 문장을 다음 하나의 규칙으로 바로잡습니다.

> 일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다.

- **보행 중**: 일반 활동원본을 어떤 망으로도 전송하지 않는다.
- **보행 정지 후, 이동통신망 전송을 명시적으로 선택했고 현재 망이 허용됨**: 배터리·저장공간 등 다른 전송 조건도 충족하면 이동통신망 전송을 허용한다.
- **보행 정지 후, 이동통신망 전송을 선택하지 않음**: Wi-Fi에서만 전송한다.
- **허용된 망이 없음**: 암호화 대기열에 보관하고 기존 보존·용량·삭제 정책을 적용한다.

## 영향 범위

- 직접 영향 산출물: REQ-03, REQ-06, DES-04, DES-13, DES-20
- 함께 갱신할 기록: REQ-16 (`docs/deliverables/03-requirements/rtm.json`), REQ-18 (`docs/deliverables/03-requirements/requirement-change-log.json`), DES-06 (`docs/deliverables/04-design/design-traceability-register.json`), MGT-14 (`docs/deliverables/01-management/registers/raid.json`), MGT-16 (`docs/deliverables/01-management/registers/change-requests.json`), DOC-05 (`docs/deliverables/00-control/artifact-change-log.json`)
- 승인되면 만들 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`

기존 정책 1.0.0 파일은 수정하거나 덮어쓰지 않았습니다. 이 후보는 뒤에서 만드는 산출물 일괄 승인문에 정확한 지문으로 함께 묶일 때만 효력이 생깁니다.

문서 작성·시험 계획 수립은 계속할 수 있지만, **일괄 승인 전에는 이동통신망 전송 분기의 정식 구현을 시작하지 않고 관련 정식 시험도 실행하지 않습니다.**

## 남아 있는 출시 gate

- `GATE-PHONE-QUEUE-BYTE-LIMIT` — 휴대전화 대기자료의 실제 용량 한도: `NOT_RUN`
- `GATE-SERVER-CAPACITY-STATE-CONTRACT` — 서버 용량상태를 휴대전화에 전달하는 규칙: `NOT_RUN`
- `GATE-RAW-COLLECTION-RELEASE-REVIEW` — 무가림 원본 수집의 출시 전 독립 검토: `NOT_RUN`
- `GATE-CLOUD-COST-MEASUREMENT` — 실제 클라우드 저장비 측정: `NOT_RUN`
- `GATE-SINGLE-ADMIN-RECOVERY-DRILL` — 관리자 휴대전화 분실 복구훈련: `NOT_RUN`

정정 후보 작성은 구현 적합성·시험 완료·gate 면제·출시 승인이 아닙니다. 출시 상태는 `NOT_ELIGIBLE`입니다.
