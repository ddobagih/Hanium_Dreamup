# D16 및 기준 문서 독립 표본 검토

검토자 baseline_audit. 작성자 Root/entry_inventory와 분리. 제품 시험·빌드·기기·외부서비스 실행 없음. 정적 소스·문서 대조만 수행.

## 검토 범위

- 00-baseline.md의 freeze 199/342,6489/6196,CSV942 상태 집계와SHA 재확인. 추가 지적 없음.
- 01-repository-map.md의 manifest/Application/Main,관리자 조건과직접origin,Gateway/Backend router,modelconfig,Web410. 추가 지적 없음.
- F/P-D16-002 키 저장: PrivateStorage.kt79–191,AnalysisCoordinator.kt240–253/616–627,MainActivity.kt89–98.
- F/P-D16-010 PPK: PpkEngines.kt83–215,walksafe_ppk_engine.c15/170–280,walksafe_ppk_jni.c108–155.
- F/P-D16-012 평가: PpkEvaluation.kt134–258.
- F/P-D16-014 취소: AnalysisCancellation.kt7–55,AnalysisCoordinator.kt367–426/641–693.
- F/P-D16-016 삭제: AnalysisCoordinator.kt215–225/443–484/747–767.
- 위5기능 그림 및 overview/cancellation-state/pipeline-sequence. 주요8소스 hash는 freeze manifest와 일치.

## 발견과 조치

|ID|심각도|발견|조치|
|---|---|---|---|
|DOC-D16-01|P2|그룹 내부 canonical 중복을 먼저 병합하고 이후 그룹 간 중복을 거절하는데 모두 금지로 단순화|Root JSON F010/P010 및 흐름도 수정; 독립 재검토 해결 확인|
|DOC-D16-02|P2|F014 기존 취소 문구 억제 분기가 새 분석 시작으로 연결됨|Root H→J 삭제; 독립 재검토 해결 확인|
|DOC-D16-03|P2|JNI 취소/오류가 FIX 평가로 이어지는 시퀀스|Root 성공/취소/일반오류 분리 및 FailurePolicy 분류 명시; 독립 재검토 해결 확인|

R-D16-002 완료 뒤 cleanup 경고 차단 가능성은 정적 경로와 일치; 제품 결함 확정/실행 재현으로 해석하지 않는다.

## 검토 버전

00 SHA d3a4478f005f94a30a927f18026883925e7de56184af6e60f8a527f5491930d2

01 SHA 3b168fdebc8e3312d32d40ca8f46ba61f15d5415b1741ba222c2a9942b1438c8

D16 시작32c7effe473fcd9edff907f567161d4008297003ea387fd36e540c139fd7ab3e → 검토 종료4f74d9ec668c8aa5fee8e4a4858a46fff86d1db7f8fbdab58e709bbd6c982a73. 중간 변경은 Root의 문서 상태별칭 설명 추가. 위 수정은 검토 종료 이후 수행.

## 미검토

나머지11기능 전체, vendor 수치 알고리즘, 모든시험/fixture, 실제NGII/Keystore/JNI/SAF, 저장소 전체 호출 그래프/architecture 그림, Mermaid 렌더·시각QA 미검토. Root가 renderer 및 별도 표시 점검을 담당한다.

## 수정 후 독립 재검토

DOC-D16-01/02/03 모두 해결, 변경된 세 부분 추가 지적 없음. JSON SHA 읽기 전후 f0edb4ca3e8e06c05251b1761017dc83378f6683a6db7a86b9a461a27f084b4d 동일. F014 SHA11362f8f1a4cd0a3ae328a4fbe93198daa7809f84355d84c7d8d2c3e46155df9, sequence SHA9fc3b5769fcdb2e361bfb53edbaaf58852493c0b9b4c6bdb2161b5079099ea09. 기존 미검토 범위 유지.
