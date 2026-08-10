# WalkSafe 7~12 정식 산출물 구조·추적 결과

> 이 문서는 SEC·AIML·REL·OPS·WS·CLS 산출물의 작성 상태와 추적을 설명합니다. 구조 검증 통과는 시험 PASS, 사람 승인 또는 출시 가능을 뜻하지 않습니다.

## 현재 결과

- 전체 유형: **129개**
- 문서 묶음: **19개**
- 작성 가능한 정식 Draft: **77개**
- 실행·서명·증거가 없어 Planned/NOT_RUN: **52개**
- 작성·검토 또는 계속 갱신할 원장 계약: **77개**
- 실제 실행·측정·외부 원본·종료사건이 필요한 계약: **52개**
- 승인된 산출물: **0개**
- 미실행 gate: **5개**, 모두 미면제
- 출시 상태: **NOT_ELIGIBLE**

| 범주 | Draft | Planned/NOT_RUN |
|---|---:|---:|
| SEC | 14 | 5 |
| AIML | 14 | 12 |
| REL | 11 | 11 |
| OPS | 21 | 3 |
| WS | 10 | 12 |
| CLS | 7 | 9 |

## 상태 해석

- `Draft`: 계획·정책·절차·명세 또는 사전개설 원장 구조가 작성됐지만 사람 승인 전입니다.
- `Planned/NOT_RUN`: 실제 scan·평가·현장시험·배포·서명·운영·종료 결과가 아직 없습니다.
- 실행 증거가 생기면 입력 build·model·config·환경·참여 동의·hash를 고정한 새 evidence instance로 추가합니다.
- FP-035는 일반 활동원본을 보행 중 전송하지 않고, 정지 뒤 이동통신망 명시 선택 시 이동통신망을 허용하며 미선택 시 Wi-Fi만 허용하는 것으로 정규화 지시를 포착했습니다. 정정 후보는 `NOT_APPROVED/NOT_EFFECTIVE`이고, 새 산출물 묶음 승인 전 관련 시험은 `NOT_RUN`이며 같은 정책 선택을 다시 묻지 않습니다.
- WS-16 Web/PWA 시험은 재승인 전 비활성입니다. 현재 제품 검증은 Android 사용자 앱과 별도 Android 관리자 앱 기준입니다.

## 분야별 manifest

- `docs/deliverables/manifests/sec-ws-draft-20260721-r001.json` — 범위 41개, Draft 24개, Planned 17개
- `docs/deliverables/manifests/aiml-draft-20260721-r001.json` — 범위 26개, Draft 14개, Planned 12개
- `docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json` — 범위 62개, Draft 39개, Planned 23개

기계 판독용 129개 상세 기록은 [formal-7-12-integration-report-20260721-r001.json](formal-7-12-integration-report-20260721-r001.json)에 있습니다.

보고서 내용 지문: `a8885f69e8b2db42ae5ecff751840262881adae16b91c8028ed2a25e6ddf6c10`
