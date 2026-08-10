# WalkSafe 시험 구현·실행 증거

> 포함 산출물: TST-06, TST-07, TST-08, TST-09, TST-10, TST-11, TST-12, TST-13, TST-14, TST-15, TST-16, TST-17  
> 버전: 0.1.0 · 상태: Draft · 승인: 미승인  
> 정책 기준선: WalkSafe 기능 정책 1.0.0  
> 검증: 아직 실행하지 않음 · 출시: NOT_ELIGIBLE

## 이 문서가 답하는 질문

각 시험 유형을 실제로 실행했으며 어느 build·환경의 어떤 결과인지 증명할 수 있는가?

## 쉬운 요약

이 문서는 확정된 기능 정책을 개발과 시험에 옮기는 첫 초안입니다. 저장소에 코드나 시험 파일이 있다는 사실만으로 기능이 완성됐거나 시험에 합격한 것은 아닙니다. 같은 코드·앱·모델·설정 묶음으로 다시 확인해야 합니다. 출시 전에 반드시 끝내야 할 검증 5개도 남아 있습니다.


## 현재 결론

12개 시험 유형 모두 정식 실행 0건입니다. 기존 시험 코드와 과거 결과는 다시 확인할 후보 목록이며 이 문서의 합격으로 계산하지 않습니다. 증거 구조와 저장 규칙은 [evidence/README.md](evidence/README.md)를 따릅니다.

<a id="tst-06"></a>
## TST-06 단위 테스트

- 적용성: `ACTIVE`
- 실행 상태: `NOT_RUN`
- 목적: 순수 정책·parser·상태기계·계산을 격리해 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-07"></a>
## TST-07 통합 테스트

- 적용성: `ACTIVE`
- 실행 상태: `NOT_RUN`
- 목적: Android·서버·DB·모델·저장 흐름을 같은 build에서 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-08"></a>
## TST-08 API·계약 테스트

- 적용성: `ACTIVE`
- 실행 상태: `NOT_RUN`
- 목적: OpenAPI, 인증, TMAP, 신고, 삭제, 중복 계약을 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-09"></a>
## TST-09 E2E 테스트

- 적용성: `ACTIVE`
- 실행 상태: `NOT_RUN`
- 목적: 가입부터 보행·탐지·길안내·신고·권리행사까지 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-10"></a>
## TST-10 사용자 인수 테스트

- 적용성: `ACTIVE_BEFORE_LIMITED_USER_TEST`
- 실행 상태: `NOT_RUN`
- 목적: 대상 사용자가 승인 요구를 실제로 인수하는지 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-11"></a>
## TST-11 회귀 테스트

- 적용성: `ACTIVE`
- 실행 상태: `NOT_RUN`
- 목적: 수정한 기능과 영향 범위가 기존 안전 동작을 깨지 않는지 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-12"></a>
## TST-12 성능·부하·스트레스

- 적용성: `ACTIVE_BEFORE_BETA`
- 실행 상태: `NOT_RUN`
- 목적: 기기 추론·배터리·발열과 서버 용량·비용을 측정한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-13"></a>
## TST-13 호환성 테스트

- 적용성: `ACTIVE_BEFORE_BETA`
- 실행 상태: `NOT_RUN`
- 목적: 지원 Android 기기·OS·capability matrix를 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-14"></a>
## TST-14 접근성 테스트

- 적용성: `ACTIVE`
- 실행 상태: `NOT_RUN`
- 목적: TalkBack, 초점, 터치, 음성·진동 대체경로를 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-15"></a>
## TST-15 사용성 테스트

- 적용성: `ACTIVE_BEFORE_RELEASE`
- 실행 상태: `NOT_RUN`
- 목적: 전맹·저시력 사용자의 독립 조작과 이해 가능성을 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-16"></a>
## TST-16 장애·복구 테스트

- 적용성: `ACTIVE_BEFORE_BETA`
- 실행 상태: `NOT_RUN`
- 목적: 권한 철회·오프라인·저장공간·외부 장애·안전정지를 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

<a id="tst-17"></a>
## TST-17 설치·업데이트·이전 버전 복귀

- 적용성: `ACTIVE_BEFORE_BETA`
- 실행 상태: `NOT_RUN`
- 목적: 앱·모델·DB의 설치, 교체, 실패 복구를 확인한다.
- 증거: 아직 없음. 실행 시 `evidence/executions/<run-id>.json`에 새 파일을 만들고 [evidence/evidence-register.json](evidence/evidence-register.json)의 구조를 따른다.

## 남은 필수 검증

- GATE-PHONE-QUEUE-BYTE-LIMIT — 휴대전화 대기자료의 실제 용량 한도: 아직 실행하지 않음, 면제되지 않음
- GATE-SERVER-CAPACITY-STATE-CONTRACT — 서버 용량상태를 휴대전화에 전달하는 규칙: 아직 실행하지 않음, 면제되지 않음
- GATE-RAW-COLLECTION-RELEASE-REVIEW — 무가림 원본 수집의 출시 전 독립 검토: 아직 실행하지 않음, 면제되지 않음
- GATE-CLOUD-COST-MEASUREMENT — 실제 클라우드 저장비 측정: 아직 실행하지 않음, 면제되지 않음
- GATE-SINGLE-ADMIN-RECOVERY-DRILL — 관리자 휴대전화 분실 복구훈련: 아직 실행하지 않음, 면제되지 않음

## 종합 상태

- formal execution: 0
- PASS: 0
- test completion: 주장하지 않음
- release: NOT_ELIGIBLE
