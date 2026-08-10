# 257종 산출물 현재 사실 감사 기준선

- 기준선 ID: `WS-ARTIFACT-AUDIT-BASELINE-20260726`
- 생성 시각: `2026-07-26T17:21:10+09:00`
- 성격: 저장소 근거에 대한 `CURRENT_FACTUAL_AUDIT`
- 권한 경계: 이 문서는 정식 승인, 외부 서명, 현장·실기기·운영 실행, 릴리스 완료를 부여하거나 주장하지 않는다.

## 검증 결과

| 검사 | 결과 | 요약 |
|---|---:|---|
| `SOURCE_SCHEMA` | `PASS` | 4개 패킷의 공통 최소 스키마, 필드 형식, 열거값, 선언 scope를 검증함 |
| `SOURCE_SUMMARY_COUNTS` | `PASS` | 각 패킷 summary를 item 전수 재집계하여 일치함 |
| `REGISTER_EXACT_SET` | `PASS` | 감사 257개와 레지스터 257개 코드가 중복·누락·초과 없이 정확히 일치함 |
| `EVIDENCE_PATH_EXISTENCE` | `PASS` | 비어 있지 않은 증거 경로 556개가 모두 저장소에 존재함 |
| `CLASSIFICATION_EXTERNAL_DEPENDENCY` | `PASS` | EXTERNAL은 의존성 필수, OK는 의존성 금지 규칙을 충족함. 내부 선행작업과 후속 외부 게이트가 함께 있는 11개는 혼합 경계로 명시함 |

## 전체 집계

| 분류 | 수량 |
|---|---:|
| `OK` | 59 |
| `INTERNAL_GAP` | 123 |
| `EXTERNAL` | 39 |
| `N/A_CANDIDATE` | 36 |
| **합계** | **257** |

| 우선순위 | 전체 | INTERNAL_GAP |
|---|---:|---:|
| `P0` | 79 | 68 |
| `P1` | 62 | 46 |
| `P2` | 18 | 2 |
| `P3` | 12 | 0 |
| `P4` | 86 | 7 |

| 접두어 | 전체 | OK | INTERNAL_GAP | EXTERNAL | N/A_CANDIDATE |
|---|---:|---:|---:|---:|---:|
| `AIML` | 26 | 1 | 16 | 4 | 5 |
| `CLS` | 16 | 0 | 7 | 3 | 6 |
| `DES` | 27 | 0 | 26 | 1 | 0 |
| `DEV` | 21 | 0 | 17 | 0 | 4 |
| `DOC` | 5 | 3 | 2 | 0 | 0 |
| `DSC` | 15 | 7 | 4 | 3 | 1 |
| `MGT` | 18 | 10 | 6 | 2 | 0 |
| `OPS` | 24 | 12 | 5 | 5 | 2 |
| `REL` | 22 | 5 | 6 | 3 | 8 |
| `REQ` | 19 | 10 | 6 | 3 | 0 |
| `SEC` | 19 | 3 | 10 | 3 | 3 |
| `TST` | 23 | 0 | 15 | 2 | 6 |
| `WS` | 22 | 8 | 3 | 10 | 1 |

## P0/P1 내부 보완 대기열

세부 사유와 요구 조치는 JSON의 각 항목에 보존했다.

### P0

- `AIML` (10): `DLV-AIML-09`, `DLV-AIML-10`, `DLV-AIML-11`, `DLV-AIML-12`, `DLV-AIML-14`, `DLV-AIML-15`, `DLV-AIML-16`, `DLV-AIML-17`, `DLV-AIML-21`, `DLV-AIML-23`
- `DES` (10): `DLV-DES-01`, `DLV-DES-03`, `DLV-DES-04`, `DLV-DES-09`, `DLV-DES-10`, `DLV-DES-13`, `DLV-DES-14`, `DLV-DES-15`, `DLV-DES-19`, `DLV-DES-20`
- `DEV` (10): `DLV-DEV-01`, `DLV-DEV-07`, `DLV-DEV-09`, `DLV-DEV-12`, `DLV-DEV-14`, `DLV-DEV-16`, `DLV-DEV-18`, `DLV-DEV-19`, `DLV-DEV-20`, `DLV-DEV-21`
- `DOC` (2): `DLV-DOC-01`, `DLV-DOC-05`
- `MGT` (4): `DLV-MGT-14`, `DLV-MGT-15`, `DLV-MGT-16`, `DLV-MGT-17`
- `REQ` (5): `DLV-REQ-03`, `DLV-REQ-06`, `DLV-REQ-16`, `DLV-REQ-18`, `DLV-REQ-19`
- `SEC` (9): `DLV-SEC-01`, `DLV-SEC-02`, `DLV-SEC-03`, `DLV-SEC-06`, `DLV-SEC-09`, `DLV-SEC-10`, `DLV-SEC-11`, `DLV-SEC-12`, `DLV-SEC-15`
- `TST` (15): `DLV-TST-01`, `DLV-TST-02`, `DLV-TST-03`, `DLV-TST-04`, `DLV-TST-05`, `DLV-TST-06`, `DLV-TST-07`, `DLV-TST-08`, `DLV-TST-09`, `DLV-TST-11`, `DLV-TST-14`, `DLV-TST-18`, `DLV-TST-19`, `DLV-TST-20`, `DLV-TST-21`
- `WS` (3): `DLV-WS-08`, `DLV-WS-10`, `DLV-WS-18`

### P1

- `AIML` (6): `DLV-AIML-04`, `DLV-AIML-05`, `DLV-AIML-06`, `DLV-AIML-07`, `DLV-AIML-08`, `DLV-AIML-13`
- `DES` (16): `DLV-DES-02`, `DLV-DES-05`, `DLV-DES-06`, `DLV-DES-07`, `DLV-DES-08`, `DLV-DES-11`, `DLV-DES-12`, `DLV-DES-16`, `DLV-DES-17`, `DLV-DES-18`, `DLV-DES-22`, `DLV-DES-23`, `DLV-DES-24`, `DLV-DES-25`, `DLV-DES-26`, `DLV-DES-27`
- `DEV` (7): `DLV-DEV-02`, `DLV-DEV-03`, `DLV-DEV-04`, `DLV-DEV-05`, `DLV-DEV-06`, `DLV-DEV-08`, `DLV-DEV-17`
- `DSC` (4): `DLV-DSC-03`, `DLV-DSC-07`, `DLV-DSC-09`, `DLV-DSC-14`
- `OPS` (5): `DLV-OPS-17`, `DLV-OPS-19`, `DLV-OPS-20`, `DLV-OPS-21`, `DLV-OPS-24`
- `REL` (6): `DLV-REL-15`, `DLV-REL-16`, `DLV-REL-17`, `DLV-REL-18`, `DLV-REL-19`, `DLV-REL-22`
- `REQ` (1): `DLV-REQ-17`
- `SEC` (1): `DLV-SEC-19`

## 외부 경계

`EXTERNAL` 39개는 외부 원본, 서명자, 환경, 참여자 또는 운영 권한 없이는 완료로 전환하지 않는다.

- `AIML` (4): `DLV-AIML-01`, `DLV-AIML-02`, `DLV-AIML-03`, `DLV-AIML-22`
- `CLS` (3): `DLV-CLS-02`, `DLV-CLS-04`, `DLV-CLS-11`
- `DES` (1): `DLV-DES-21`
- `DSC` (3): `DLV-DSC-05`, `DLV-DSC-06`, `DLV-DSC-11`
- `MGT` (2): `DLV-MGT-03`, `DLV-MGT-08`
- `OPS` (5): `DLV-OPS-06`, `DLV-OPS-11`, `DLV-OPS-13`, `DLV-OPS-22`, `DLV-OPS-23`
- `REL` (3): `DLV-REL-13`, `DLV-REL-20`, `DLV-REL-21`
- `REQ` (3): `DLV-REQ-12`, `DLV-REQ-13`, `DLV-REQ-15`
- `SEC` (3): `DLV-SEC-04`, `DLV-SEC-05`, `DLV-SEC-17`
- `TST` (2): `DLV-TST-22`, `DLV-TST-23`
- `WS` (10): `DLV-WS-06`, `DLV-WS-07`, `DLV-WS-09`, `DLV-WS-12`, `DLV-WS-13`, `DLV-WS-14`, `DLV-WS-15`, `DLV-WS-17`, `DLV-WS-20`, `DLV-WS-21`

내부 작성 선행작업은 남아 있지만 이후 외부 게이트도 필요한 혼합 경계 11개: `DLV-CLS-08`, `DLV-CLS-10`, `DLV-CLS-14`, `DLV-CLS-15`, `DLV-CLS-16`, `DLV-OPS-17`, `DLV-OPS-19`, `DLV-REL-15`, `DLV-REL-16`, `DLV-REL-19`, `DLV-SEC-06`

## N/A 후보 경계

`N/A_CANDIDATE` 36개는 자동 제외가 아니다. 적용 조건과 권한 있는 판정을 확인하기 전까지 후보 상태를 유지한다.

- `AIML` (5): `DLV-AIML-18`, `DLV-AIML-19`, `DLV-AIML-20`, `DLV-AIML-25`, `DLV-AIML-26`
- `CLS` (6): `DLV-CLS-01`, `DLV-CLS-03`, `DLV-CLS-05`, `DLV-CLS-06`, `DLV-CLS-12`, `DLV-CLS-13`
- `DEV` (4): `DLV-DEV-10`, `DLV-DEV-11`, `DLV-DEV-13`, `DLV-DEV-15`
- `DSC` (1): `DLV-DSC-04`
- `OPS` (2): `DLV-OPS-07`, `DLV-OPS-18`
- `REL` (8): `DLV-REL-03`, `DLV-REL-04`, `DLV-REL-05`, `DLV-REL-06`, `DLV-REL-07`, `DLV-REL-08`, `DLV-REL-09`, `DLV-REL-14`
- `SEC` (3): `DLV-SEC-13`, `DLV-SEC-14`, `DLV-SEC-18`
- `TST` (6): `DLV-TST-10`, `DLV-TST-12`, `DLV-TST-13`, `DLV-TST-15`, `DLV-TST-16`, `DLV-TST-17`
- `WS` (1): `DLV-WS-16`

## 원본 결속

| 역할 | 경로 | SHA-256 | 항목 |
|---|---|---|---:|
| `AUDIT_PACKET` | `docs/control/execution/artifact-audits/20260726/management-audit.json` | `e57306855e81cfd52048bd6699bd7c9fc6ba3b249b436ecec68870bf3e5cf72f` | 57 |
| `AUDIT_PACKET` | `docs/control/execution/artifact-audits/20260726/technical-audit.json` | `daa9d258d9baea8cac9e82b65927b805b805b0dc4977aaa040098500886af856` | 71 |
| `AUDIT_PACKET` | `docs/control/execution/artifact-audits/20260726/security-ai-audit.json` | `4eb543de60cb07d267f52af17f725036aa79520da883ba02814456dbfe4dbd14` | 45 |
| `AUDIT_PACKET` | `docs/control/execution/artifact-audits/20260726/release-ops-ws-closure-audit.json` | `d465905e6e33c720aa776ffb48145c29ef7b8036d7d1da416641fb5c9226af44` | 84 |
| `ARTIFACT_REGISTER` | `docs/deliverables/00-control/artifact-register.json` | `c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6` | 257 |

- 비어 있지 않은 증거 경로: 556개, 누락 0개
- 정렬 기준: `artifact_type_code` 오름차순
