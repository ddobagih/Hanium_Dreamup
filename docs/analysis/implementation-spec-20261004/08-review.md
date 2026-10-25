# 검수 결과와 미검토 범위

[전체 목차](README.md) · [기준](00-baseline.md) · [원본 불일치·추가 검토](07-review-items.md) · [진입점 대조](09-inventory.md)

## 판정의 범위

이 문서 묶음은 **고정 작업 사본의 정적 구현 명세**다. 모든 개선 항목 DONE인 최종 통합 커밋은 확인되지 않았다. 이번 작업의 성공 판정은 문서 구조·참조·연결·다이어그램의 확인에 한정한다. 제품이 기능 명세대로 실기기나 운영 서비스에서 동작한다는 판정과 분리한다. 원본 코드와 기존 기록은 수정하지 않았다.

|검사|실제 확인 결과|근거|
|---|---|---|
|분야·기능·정책|16분야 / 610기능 / 552정책|[구조 검사](evidence/document-check.json)|
|이벤트·수치|992이벤트 / 1390수치 항목|[추가 정적 검사](evidence/final-static-check.json)|
|기능 필수 항목·종료9종·상태·정책 연결·중복ID|정적 검사 오류 0건, 본문 연결 검사 오류 0건|[추가 정적 검사](evidence/final-static-check.json)|
|코드 근거|서로 다른 668개 파일의 참조 구간·snapshot hash 확인|[고정 manifest](evidence/source-manifest.json)|
|테스트 기대|연결 489건, 실행 0회. 중복 파일·심볼 연결 포함|[기능 대응표](evidence/feature-event-state-policy-test.csv)|
|Mermaid 문법·실제 SVG 생성|766개 중 766개 PASS|[렌더 기록](evidence/render-results.json)|
|SVG 단독 파일 XML|766/766 정상 파싱|[추가 정적 검사](evidence/final-static-check.json)|
|원본 보존|6489개 hash 대조, 누락 0, 허용 외 변경 0|[보존 검사](evidence/source-preservation-check.json)|
|허용된 기존 파일 변경|작업 기록용 daylog/2026-10-04.md 추가 기록|[daylog](../../../daylog/2026-10-04.md)|

SVG 생성 성공만으로 웹에서 그림이 표시된다고 단정하지 않았다. 초기 Mermaid HTML label의 XML 직렬화 문제를 발견해 XMLSerializer로 바로잡고 재생성했다. SVG에 실제 viewBox 크기를 width/height로 지정해 내장 이미지가 기본300px 크기로 축소되는 문제도 예방했다. 최종 브라우저의 그림 decode·내부 링크·검색 UI·가로 넘침 검사 및 대표 화면 시각 검토 기록은 [표시 검사](evidence/browser-check.json), [시각 검사](evidence/visual-check.json), [미리보기](evidence/previews/)로 남긴다. 모든 그림을 사람이 모든 해상도에서 전수 읽었다는 뜻은 아니다.

## 독립 검토

분야 작성자와 다른 담당자가 핵심 기능의 소스·정책·도표를 표본 대조했다. 검토 문서는 대상 JSON SHA, 실제 본 범위, 지적, 수정 후 재확인, 미검토 범위를 보존한다. 후속 자체 보강이 기존 검토 범위 밖이면 그 차이도 기록했다. 표본을 전체 610기능의 독립 검토로 확대하지 않는다.

문서 검토에서 확인한 오류에는 배타 분기의 직렬 연결, 조기 return 누락, 취소 뒤 새 작업 자동 시작처럼 보이는 도식, 부정확한 임계 경계·출처, 실제 thread·callback 소유권, 원격 영수증과 로컬 정리 성공의 혼합이 있다. 담당 작성자가 문서만 수정하고 검토자가 영향 범위를 다시 확인했다. 남은 제품 의심·문서 요구와 코드의 차이는 [별도 검토 목록](07-review-items.md)에 두었다.

|검토 보고서|파일 SHA-256|
|---|---|
|[D01-by-D12.md](reviews/D01-by-D12.md)|051d473e89c356e5f7b88c2616d3521ddb403e8d398cedfea56afae6b3160050|
|[D02-by-D06.md](reviews/D02-by-D06.md)|776a304e442de8fc8bf7c2529c5ce7b814e7376a2726598bf484a25b9525beeb|
|[D03-by-D04.md](reviews/D03-by-D04.md)|95d3b9e6b8489c888e8a7a859b03684dace13beb486a178b7ca5864d07c1d039|
|[D04-by-D03.md](reviews/D04-by-D03.md)|4df9d7fc8328a52110bcdf1c0698121e0f66314d5928e81789c7cf8366f89e83|
|[D04-debug-by-D11.md](reviews/D04-debug-by-D11.md)|acc95342d72b8bd93aafc5a0ea6935c0407749301f2b159fa0871a4bb73d5812|
|[D05-by-D07.md](reviews/D05-by-D07.md)|bfa541bf5dd1c4345359d636fda6fe4844c4fbb3d15006579f62d3e084ce6dc0|
|[D06-by-D02.md](reviews/D06-by-D02.md)|06b7f1d3ded46151b291cf170e2470e1e5647201e640f67f453b2d34565c6a7c|
|[D07-by-D05.md](reviews/D07-by-D05.md)|0bb1f63efc0039cbb30b859b2560efb14a516ecf9d809b8f825573052a7c876c|
|[D08-by-D14.md](reviews/D08-by-D14.md)|c885f29c85f281c59ec6e050ba2ea8a02e434986a173e2c0456d436c6ae6265e|
|[D09-by-D08.md](reviews/D09-by-D08.md)|d878ac9b8e554a2268ae72acd23afee833415d7a57005701cc399548da14a04c|
|[D10-by-D09.md](reviews/D10-by-D09.md)|52f855ac824d5fe81881ed8831af57ff2267f4231fde42bd791ee637da80105d|
|[D11-by-D10.md](reviews/D11-by-D10.md)|28f6cf561a42082fd773a5649019fa344550a8b536f26fdcbebbebe1426ce2f7|
|[D12-by-D01.md](reviews/D12-by-D01.md)|157bb4257b3202a01f4cbd1a394c6d2a8dab326057c6d3fc54e04119ff36c015|
|[D13-by-D11.md](reviews/D13-by-D11.md)|a44930f24ab8bcb9cbbaf84f47f1de1a4df2cb9bb9e651bda5a1c7c866de0026|
|[D14-by-D15.md](reviews/D14-by-D15.md)|a211baca3a4c8f144ffd0fbfb5d8d511c3f826ee733928bae8b3de1a0edfad05|
|[D15-by-D13.md](reviews/D15-by-D13.md)|86a03485e3ee348d11023534394701ec1e5a3868776c971ed18b78b8efded239|
|[D16-by-baseline.md](reviews/D16-by-baseline.md)|67aad4a92b8939eca6e3f52fa9b1d3aba9ce308f5ba9c3751733977870bcf254|
|[scenarios-01-03-by-baseline.md](reviews/scenarios-01-03-by-baseline.md)|c6e65b8b918c30baf3f7b6a117130478e4baa2fbb31e397fa04459dfc0d03f2b|
|[scenarios-04-07-by-D10.md](reviews/scenarios-04-07-by-D10.md)|786696f25f782d3a7bfbc5916ac3fafb85c6f26585934c4120c2c3b94312284f|

## 진입점과 기존 개선 항목의 대조

707개 선언 후보의 대응 수준은 다음과 같다. SOURCE_RANGE_REFERENCED도 해당 함수의 모든 분기를 전수 검토했다는 뜻은 아니다. 범위가 큰 파일에서 선언 줄이 인용 범위에 포함되는지 검사한 결과다.

|대응 수준|개수|의미|
|---|---:|---|
|SOURCE_RANGE_REFERENCED|182|선언 줄이 연결 기능의 코드 참조 범위 안에 있음|
|FILE_REFERENCED_OUTSIDE_RANGE|206|같은 파일 연결만 확인, 정확 선언/분기 대응은 제한됨|
|INVENTORY_FAMILY_MAPPING|318|개발·역사 도구 계열 인벤토리 연결, 개별 본문 전수 분석 아님|
|READ_FILE_INVENTORY_ONLY|1|읽은 파일 목록만 연결, 해당 선언의 상세 기능 대응 미확인|
|UNMAPPED|0|현재 패턴으로 발견했지만 대응을 못 찾은 선언|

[상세 진입점 대응](evidence/entrypoint-feature-map.csv) · [잔여 항목](evidence/entrypoint-unmapped.csv).

추가로 설정 식별자 316개, 테스트 파일 1438개, 음성 command/action 선언 38개와 화면 관련 선언을 수집했다. 이는 명칭 대조이며 모든 설정값·전체 테스트 단언의 분석이나 실행이 아니다. 6개 NativeUiPage와 별도 관리/교육/삭제/디버그 화면 경계를 구분했다.

기존21분야·942항목과 A189/B188의 대응에는 **직접 ID / 공유 파일·시험 후보 / 분야만 연관**의 서로 다른 강도를 사용했다. 후보 대응을 개선 인수 조건 충족이나 DONE로 승격하지 않았다. 원문 status는 그대로 보존했다. [대응 기준과 통계](04-traceability.md)를 따른다.

## 남은 범위와 해석 제한

- 분석 기준은 미커밋 변경을 포함한다. 파일별 줄 번호는 순수 HEAD와 다를 수 있다. 원본 경로 링크는 현재 파일을 열기 때문에 이후 변경 시 manifest와 대조해야 한다. 임시 고정 text snapshot의 위치와 포착 시각을 기록했다.
- 대형 Main·공유 helper·DB migration·과거 개발 스크립트는 분야별 선택 구간 또는 계열 수준 조사다. 파일 이름만 읽은 항목과 본문을 대조한 항목을 구분한다. D11의863파일 대응표와 모든 분야 coverage.unreviewed를 참조한다.
- 제3자 native/RTKLIB 수치 알고리즘, 전체 테스트 fixture·학습 데이터·모델 성능, 운영 secrets·실제배포 설정, 반사/동적 등록의 완전 호출 그래프는 전수 검토하지 않았다.
- 이번 JVM/pytest/Node 제품시험·빌드·APK설치·기기청취·센서·필드·TMAP·NGII·SMTP·DB 운영 실행은 0회다. 문서 생성용 파서와 브라우저 검사만 실행했다.
- 과거 시험과 APK 증거는 적용되는 소스 hash 범위만 인정했다. 특히 MainActivity 불일치와 미해결 모델 관찰은 최신 통합 검증으로 일반화하지 않았다.
- ACTIVE/CONDITIONAL 등은 코드 연결 판단이며 운영 성공이 아니다. 미연결·비활성 코드도 기능 목록에 포함했다. S- 상태 ID 중 문서 별칭은 실제 enum과 구분한다.
- 정의되지 않은 전역 우선순위·정책 충돌 규칙을 추가하지 않았다. 특정 모듈의 fence·TTL·동의·현재성 보장을 전체 프로젝트 공통 보장으로 확대하지 않는다.

현재 구현에 대한 사실과 추가 확인·개선 제안은 분리되어 있다. 발견한 문제를 이 작업에서 수정하지 않았으며, 검토 표본 밖까지 ‘모든 기능을 빠짐없이 확인’했다고 선언하지 않는다.
