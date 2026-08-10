# Wave2 UI·접근성 현재 상태 후속 보충본 v2

- 대상: `DLV-DES-14`, `DLV-DES-16`, `DLV-DES-17`, `DLV-DES-18` exact 4개
- source commit: `null`; dirty worktree exact path/SHA binding
- 정적 prototype: [prototype-current-state.html](prototype-current-state.html) · SHA-256 `4781961172dde0c1bfea932194137ea620af005a91dacdff90a8802e03a63356`
- prototype trace: [prototype-trace.json](prototype-trace.json) · fingerprint `4be2b74018f4d3d3cc85db42009c84144ee0a8fa3632e0c0313d6da1beaff826`
- design token contract: [design-token-contract.json](design-token-contract.json) · fingerprint `dd318743588e46ff01810bb32a1b10dfd41aebc4f04a7eb2b2a904208dc595a4`
- current-state fingerprint: `758d25b6ea30f7631eefec65ad41c7b822e008dc2e35e83f30d1583ea29aaf39`

## 보완 판정

`DES-16`에는 user 5개, admin 5개 총 10개 결정론적 HTML frame을 추가했다. 각 frame은 `loading/empty/error/blocked/safety/recovery` 상태 matrix의 한 행, 한 route, 실제 controller symbol, token과 component contract에 1:1로 연결된다. HTML은 JavaScript와 외부 asset이 없는 정적 검토 기준선이며 실제 기기 screenshot이 아니다.

`DES-17`에는 공통 semantic color, typography, spacing, focus, announcement, risk, safe, admin-lock token과 8개 component contract를 추가했다. 현행 두 앱의 literal과 component는 모두 `TOKEN_MAPPED` 또는 `UNAPPROVED_DEVIATION`으로 분류했다. 이 매핑은 제품 코드가 token resource를 이미 소비한다는 뜻이 아니다.

## 실제 앱 경계

| 앱 | route | controller | 현행 navigation |
|---|---|---|---|
| 사용자 | `ANDROID-USER-LAUNCHER` | `MainActivity` | 단일 portrait Activity의 programmatic overlay/ScrollView와 state visibility |
| 관리자 | `ANDROID-ADMIN-LAUNCHER` | `AdminBoundaryActivity` | 별도 단일 Activity의 인증·복구·session·high-risk lock group |

historical Figma/PWA는 현행 authority나 frame source로 사용하지 않았다.

## 상태와 frame

| 앱 | 상태 | frame |
|---|---|---|
| User | loading | `FRAME-U-LOADING` |
| User | empty | `FRAME-U-EMPTY` |
| User | error | `FRAME-U-ERROR` |
| User | blocked | `FRAME-U-BLOCKED` |
| User | safety | `FRAME-U-SAFETY` |
| Admin | loading | `FRAME-A-LOADING` |
| Admin | empty | `FRAME-A-EMPTY` |
| Admin | error | `FRAME-A-ERROR` |
| Admin | blocked | `FRAME-A-BLOCKED` |
| Admin | recovery | `FRAME-A-RECOVERY` |

위험·안전·관리자 잠금은 색 외에도 `[위험]`, `[안전 기능 실행]`, `[잠김]` text prefix, icon label, action/reason, polite/assertive announcement 계약을 가진다.

## artifact별 exactly one gap disposition

| artifact | gap ID | owner | target | 종료조건 요약 |
|---|---|---|---|---|
| DES-14 | `W2-UX-GAP-DES14-001` | Android·QA·접근성 | W4 | 실제 launcher·화면·전이·회복 capture |
| DES-16 | `W2-UX-GAP-DES16-001` | 제품·Android·QA | W4 | 고정 build screenshot hash와 prototype 차이 판정 |
| DES-17 | `W2-UX-GAP-DES17-001` | 기술·Android·접근성·QA | W4 | deviation 처리와 대비·큰 글꼴·focus·48dp 측정 |
| DES-18 | `W2-UX-GAP-DES18-001` | 접근성·Android·QA | W4 | TalkBack tree·focus·announcement·reflow 실기기 검증 |

각 gap의 exact `closure_condition`과 `expected_evidence_paths`는 JSON에 기록했다.

## 변하지 않은 경계

- 제품 코드, build, test, screenshot, legacy 정본·승인은 수정하지 않았다.
- actual-device screenshot, TalkBack, accessibility tree, 실제 전맹·저시력 사용자, 큰 글꼴/reflow, 대비, focus, touch target은 모두 `NOT_RUN`이다.
- 정식시험 279개는 모두 `NOT_RUN`, release gate 5개는 미실행·미면제, release는 `NOT_ELIGIBLE`이다.
