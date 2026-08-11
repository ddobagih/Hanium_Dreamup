# 현대화 전 기준선

## 저장소

- 브랜치: `current`
- HEAD: `f0093863e82bfc80d9f11915cef33a51d44b8730`
- tree: `a5a1ba3bd2b5d453cecdd3556507db33fc253c96`
- 원격 `current`: HEAD와 동일
- 원격 `main`: `c38b2591270b4650ec3376d11ef2c42bb9a1ae01`
- GitHub 기본 브랜치: `current`
- 공개 범위: private
- 추적 파일: 3,429개

## 검사 기준선

| 검사 | 결과 | 출력 SHA-256 | 판정 |
|---|---:|---|---|
| `check_walksafe_project_continuation_v2_4.py` | exit 0 | `25b5c8cf65854e11a04a9d0810dd2503585037683809c153f578c08a076ac5a7` | PASS |
| `check_walksafe_goal_graph_v2_4.py` | exit 1, 문제 63개 | `bfab9a20b8ab621f94b45dc27d153a03d0c9062c398fb9ac51a96e13ee098417` | 기존 standalone history 경계 |
| `PYTHON_BIN=/home/ddobagi/.local/share/hanium-dreamup/walksafe-general-cpu-verify-20260715/bin/python` test-layer `validate` | exit 2 | `3351fa70266c34eaf6faea2c0ddbd6405ea2aae832429e6b78522ddba36d02b4` | 누락 경로 1개, 이번 정비 대상 |

Goal graph 63건은 FP014 tail과 frozen v2.2/v2.3 changed-artifact 비교에 필요한 과거 base object가 standalone `current`에 없어서 발생한다. 현대화 검수에서는 이 63개를 기존 실패 집합으로 고정하고 새 실패가 추가되지 않는지 비교한다.

test-layer 기준선의 직접 원인은 `tests/test_walksafe_v2_5_control_candidate_20260730.py`가 등록돼 있으나 파일이 존재하지 않는 것이다.

## 제품·시험 상태

- 현행 제품: Android 사용자 앱, 별도 Android 관리자 앱
- 지원 서비스: Android Gateway, Backend, 모델·데이터·계약·배포 도구
- Legacy Web: `LEGACY_REFERENCE_ONLY`
- 정식 시험: 279/279 `NOT_RUN`
- 실제 기기·현장·운영 배포·외부 수락: `NOT_RUN`
- release: `NOT_ELIGIBLE`
