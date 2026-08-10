# WalkSafe Test Log API

로컬 개발자가 사용 동의를 받은 detector 화면·metadata를 검토용으로 저장하는 독립 App Router endpoint다.

## 책임

- `route.ts`: enable, production block, bearer token, consent header, 크기·ID·경로 검증, retention 정리와 파일 저장
- 저장 루트: `WALKSAFE_TEST_LOG_DIR` 또는 기본 `walksafe-test-logs/`
- 사용 목적: detector/policy의 수동 검토용 snapshot

## 안전 경계

- `NODE_ENV=production`에서는 항상 거부한다.
- `WALKSAFE_TEST_LOG_ENABLED=true`, auth token과 consent header가 모두 필요하다.
- 운영 신고, 자동 학습 데이터 수집, 성능·현장 검증 근거로 사용하지 않는다.
- raw capture는 개인정보를 포함할 수 있으므로 외부 제출문서에 넣지 않는다.

상위 API proxy와의 관계는 [`../README.md`](../README.md)를 따른다.
