# Web Domain and API Library

이 디렉터리는 React에 의존하지 않는 API client와 정책을 제공한다.

- `detect-api*.ts`, `report-api*.ts`, `navigation-api.ts`, `voice-api.ts`: backend/voice HTTP 계약과 오류 정규화
- `detector*.ts`: fake detector fixture
- `auto-report-v2.ts`, `two-model-priority.ts`: v2 신고/표시 대상 선택
- `offline-report-queue.ts`: 명시적 opt-in을 요구하는 bounded queue 순수 정책

API client는 browser가 offline이면 요청을 보내지 않는다. wire payload를 바꿀 때는 `types/`, backend schema와 해당 policy test를 함께 확인한다.
